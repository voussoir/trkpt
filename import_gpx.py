import argparse
import bs4
import dateutil.parser
import sqlite3
import sys

from voussoirkit import betterhelp
from voussoirkit import pathclass
from voussoirkit import vlogging

log = vlogging.getLogger(__name__, 'import_gpx')

def import_gpx_argparse(args):
    gpxs = pathclass.glob_many_files(args.patterns)
    sql = sqlite3.connect(args.dbfile)
    for gpx in gpxs:
        print(gpx.absolute_path)
        soup = bs4.BeautifulSoup(gpx.read('r'), 'xml')
        if soup.gpx.metadata and soup.gpx.metadata.device:
            device_id = soup.gpx.metadata.device.text
        else:
            device_id = 'import_gpx'
        for trkpt in soup.find_all('trkpt'):
            lat = trkpt['lat']
            lon = trkpt['lon']
            provider = 'gps'
            if trkpt.unix:
                time = int(trkpt.unix.text)
            else:
                time = int(dateutil.parser.parse(trkpt.time.text).timestamp() * 1000)
            if trkpt.accuracy:
                accuracy = trkpt.accuracy.text
            else:
                accuracy = 0
            if trkpt.ele:
                ele = trkpt.ele.text
            else:
                ele = None
            if trkpt.sat:
                sat = trkpt.sat.text
            else:
                sat = None
            bindings = [
                device_id,
                time,
                lat,
                lon,
                provider,
                accuracy,
                ele,
                sat,
            ]
            try:
                sql.execute('INSERT INTO trkpt (device_id, time, lat, lon, provider, accuracy, ele, sat) VALUES(?, ?, ?, ?, ?, ?, ?, ?)', bindings)
            except sqlite3.IntegrityError:
                pass
    sql.commit()
    return 0

@vlogging.main_decorator
def main(argv):
    parser = argparse.ArgumentParser(
        description='''
        This program can import your existing GPX files into a trkpt database.

        Make sure you stop and close trkpt before modifying the database so
        that the changes don't clash with an ongoing recording.
        ''',
    )
    parser.add_argument(
        'dbfile',
        help='''
        Path to the .db file.
        ''',
    )
    parser.add_argument(
        'patterns',
        nargs='+',
        help='''
        One or more glob patterns that will match your .gpx files.
        ''',
    )
    parser.set_defaults(func=import_gpx_argparse)

    return betterhelp.go(parser, argv)

if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1:]))
