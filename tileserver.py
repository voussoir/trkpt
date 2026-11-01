import argparse
import flask; from flask import request
import gevent.monkey; gevent.monkey.patch_all()
import gevent.pywsgi
import io
import math
import PIL.Image
import random
import sqlite3
import sys
import typing

from voussoirkit import betterhelp
from voussoirkit import dotdict
from voussoirkit import operatornotify
from voussoirkit import pipeable
from voussoirkit import vlogging

log = vlogging.getLogger(__name__, 'trkpt_tileserver')

SELECT_MODULUS = {
    1: 16384,
    2: 8192,
    3: 4096,
    4: 2048,
    5: 1024,
    6: 512,
    7: 256,
    8: 128,
    9: 64,
    10: 32,
    11: 16,
    12: 8,
    13: 4,
    14: 2,
    15: 1,
    16: 1,
    17: 1,
    18: 1,
    19: 1,
    20: 1,
}

HEATMAP_SCALE = {
    1: 21,
    2: 20,
    3: 19,
    4: 18,
    5: 17,
    6: 16,
    7: 15,
    8: 14,
    9: 13,
    10: 12,
    11: 11,
    12: 10,
    13: 9,
    14: 8,
    15: 7,
    16: 6,
    17: 5,
    18: 4,
    19: 3,
    20: 2,
}

def tms_x_to_lon(x: float, zoom: int) -> float:
    return (x / (2.0 ** zoom)) * 360.0 - 180.0

def tms_y_to_lat(y: float, zoom: int) -> float:
    return math.degrees(math.atan(math.sinh(math.pi * (2.0 * y / (2.0 ** zoom) - 1.0))))

def tms_to_bbox(x: int, y: int, zoom: int):
    y = (2 ** zoom) - 1 - y
    west = tms_x_to_lon(x, zoom)
    east = tms_x_to_lon(x + 1, zoom)

    # In TMS, y is the south edge and y + 1 is the north edge
    south = tms_y_to_lat(y, zoom)
    north = tms_y_to_lat(y + 1, zoom)

    return dotdict.DotDict(west=west, south=south, east=east, north=north)

def hex_to_rgb(hx):
    hx = hx.replace('#', '')
    if len(hx) == 3:
        (r, g, b) = (hx[0] * 2, hx[1] * 2, hx[2] * 2)
    else:
        (r, g, b) = (hx[0:2], hx[2:4], hx[4:6])
    return (int(r, 16), int(g, 16), int(b, 16))

def trkpts_to_image(trkpts, zoom, bbox, color, heatmap=True):
    px_lat = (bbox.north - bbox.south) / 256
    px_lon = (bbox.east - bbox.west) / 256
    heats = [0] * (256 ** 2)
    for trkpt in trkpts:
        log.debug(f'{trkpt['lat']} {trkpt['lon']}')
        px_x = math.floor((trkpt['lon'] - bbox.west) / px_lon)
        px_y = math.floor(256 - (trkpt['lat'] - bbox.south) / px_lat)
        log.debug(f'{px_x=}, {px_y=}')
        index = (px_y * 256) + px_x
        log.debug(index)
        heats[index] += 1
    image = PIL.Image.new('RGBA', (256, 256), color=(0, 0, 0, 0))
    for (index, heat) in enumerate(heats):
        (y, x) = divmod(index, 256)
        if heatmap:
            heatlog = math.log(heat + 1, HEATMAP_SCALE[zoom])
            thiscolor = (color[0], color[1], color[2], int(color[3] * heatlog))
            # if heat > 2:
            #     print(heat, heatlog, thiscolor)
            image.putpixel((x, y), thiscolor)
        elif heat > 0:
            image.putpixel((x, y), color)
        elif vlogging.ARGV_LEVEL <= vlogging.DEBUG:
            # This helps see boundaries of each tile when debugging.
            image.putpixel( (x, y), (0, random.randint(0, 256), random.randint(0, 256), 60))
    return image

def server_argparse(args):
    site = flask.Flask(__name__)
    sql = sqlite3.connect(args.database)
    sql.row_factory = sqlite3.Row
    cache = {}

    blank_image = PIL.Image.new('RGBA', (256, 256), color=(0, 0, 0, 0))
    blank_bio = io.BytesIO()
    blank_image.save(blank_bio, format='png')
    blank_bio.seek(0)
    blank_bytes = blank_bio.read()
    color = hex_to_rgb(args.color)
    color = (*color, 255)

    @site.route('/tms/<int:zoom>/<int:x>/<int:y>.png')
    def tile_tms(zoom, x, y):
        if (zoom, x, y) in cache:
            return cache[(zoom, x, y)]
        bbox = tms_to_bbox(x=x, y=y, zoom=zoom)
        query = '''
            SELECT trkpt.lat, trkpt.lon
            FROM trkpt
            JOIN trkpt_rtree ON trkpt.id = trkpt_rtree.id
            WHERE   trkpt_rtree.max_lon >= ?
                AND trkpt_rtree.min_lon <= ?
                AND trkpt_rtree.max_lat >= ?
                AND trkpt_rtree.min_lat <= ?
                AND trkpt.lon >= ?
                AND trkpt.lon <= ?
                AND trkpt.lat >= ?
                AND trkpt.lat <= ?
                AND trkpt.deleted = 0
                AND trkpt.accuracy < 10
                AND trkpt.id % ? = 0
        '''
        bindings = [
            bbox.west,
            bbox.east,
            bbox.south,
            bbox.north,
            bbox.west,
            bbox.east,
            bbox.south,
            bbox.north,
            SELECT_MODULUS[zoom],
        ]
        trkpts = sql.execute(query, bindings).fetchall()
        if len(trkpts) > 1_000_000:
            raise ValueError()
        log.info(f'{zoom=}, {x=}, {y=} found {len(trkpts)} points.')
        # log.debug(f'Max latitude = {max(p["lat"] for p in trkpts)}')
        if len(trkpts) == 0:
            response_bytes = blank_bytes
        else:
            image = trkpts_to_image(trkpts, zoom=zoom, bbox=bbox, color=color, heatmap=args.heatmap)
            bio = io.BytesIO()
            image.save(bio, format='png')
            bio.seek(0)
            response_bytes = bio.read()
        response = flask.Response(response_bytes, headers={'content-type': 'image/png'})
        cache[(zoom, x, y)] = response
        return response

    try:
        http = gevent.pywsgi.WSGIServer(
            listener=('0.0.0.0', args.port),
            application=site,
        )
        http.serve_forever()
        # site.run(host='0.0.0.0', port=args.port)
    except KeyboardInterrupt:
        pass
    return 0

def export_argparse(args):
    return 0

@operatornotify.main_decorator(subject='trkpt_tileserver')
@vlogging.main_decorator(log=log)
def main(argv):
    parser = argparse.ArgumentParser(
        description='''
        ''',
    )

    subparsers = parser.add_subparsers()

    p_export = subparsers.add_parser(
        'export',
        description='''
        ''',
    )
    p_export.add_argument(
        'database',
        help='''
        ''',
    )
    p_export.add_argument(
        'bbox',
        help='''
        ''',
    )
    p_export.add_argument(
        '--zoom',
        required=True,
        type=int,
        help='''
        ''',
    )
    p_export.add_argument(
        '--output',
        help='''
        Filename to export to. If not provided, a name will be generated
        automatically.
        ''',
    )
    p_export.add_argument(
        '--color',
        dest='color',
        default='FF00FF',
        help='''
        A hexadecimal color code for the pixels.
        ''',
    )
    p_export.add_argument(
        '--no-heatmap',
        '--no_heatmap',
        dest='heatmap',
        action='store_false',
        help='''
        If provided, the pixels will always be rendered at full value, without
        applying the heatmap scaling function.
        ''',
    )
    p_export.set_defaults(func=export_argparse)

    p_server = subparsers.add_parser(
        'server',
        description='''
        ''',
    )
    p_server.add_argument(
        'database',
        help='''
        ''',
    )
    p_server.add_argument(
        'port',
        type=int,
        help='''
        ''',
    )
    p_server.add_argument(
        '--color',
        dest='color',
        default='FF00FF',
        help='''
        A hexadecimal color code for the pixels.
        ''',
    )
    p_server.add_argument(
        '--no-heatmap',
        '--no_heatmap',
        dest='heatmap',
        action='store_false',
        help='''
        If provided, the pixels will always be rendered at full value, without
        applying the heatmap scaling function.
        ''',
    )
    p_server.set_defaults(func=server_argparse)

    return betterhelp.go(parser, argv)

if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1:]))
