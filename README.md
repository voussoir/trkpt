trkpt
=====

<p align="center"><img src="https://raw.githubusercontent.com/voussoir/trkpt/master/trkpt_squircle_128x128.png"/></p>

This is a fork of [Trackbook](https://codeberg.org/y20k/trackbook) by y20k. Thank you y20k for this great project.

The goal of this fork is to make 24/7 recording easier. I want to be able to run trkpt nearly all of the time. The main differences between trkpt and Trackbook are:

1. trkpt stores points in an SQLite database instead of json files.

    &bull; You can put the database in a folder that you sync to your PC with [Syncthing](https://f-droid.org/en/packages/com.nutomic.syncthingandroid/).

2. trkpt does not store "tracks" as objects. Instead, tracks are rendered and exported on the fly by querying the database of trackpoints.

3. trkpt adds the feature of "homepoints". When you are near a homepoint, trackpoints are not recorded. You can put a homepoint at your house or other places where you spend lots of time, so that you don't get large clouds of useless trackpoints at those locations.

    &bull; Although Trackbook has a feature to omit points that are close together, natural GPS inaccuracy and drift is large enough to create points that are far apart, leading to clouds over time.

4. trkpt removes the feature of "starring" waypoints. I recommend using [OsmAnd](https://f-droid.org/en/packages/net.osmand.plus/) to store your favorite places.

## Mirrors

https://github.com/voussoir/trkpt

https://gitlab.com/voussoir/trkpt

https://codeberg.org/voussoir/trkpt
