<p align="center"><img src="https://raw.githubusercontent.com/voussoir/trkpt/master/trkpt_squircle_128x128.png"/></p>

trkpt
=====

This is a fork of [Trackbook](https://codeberg.org/y20k/trackbook) by y20k. Thank you y20k for this great project.

The goal of this fork is to make 24/7 recording easier. I want to be able to run trkpt nearly all of the time. I have written more about this at [voussoir.net/writing/obsessed_with_gpx](https://voussoir.net/writing/obsessed_with_gpx). The main differences between trkpt and Trackbook are:

1. trkpt stores points in an SQLite database instead of json files.

    &bull; You can put the database in a folder that you sync to your PC with [Syncthing](https://f-droid.org/en/packages/com.nutomic.syncthingandroid/).

2. trkpt does not store "tracks" as objects. Instead, tracks are rendered and exported on the fly by querying the database of trackpoints.

3. trkpt adds the feature of "homepoints". When you are near a homepoint, trackpoints are not recorded. You can put a homepoint at your house or other places where you spend lots of time, so that you don't get large clouds of useless trackpoints at those locations.

    &bull; Although Trackbook has a feature to omit points that are close together, natural GPS inaccuracy and drift is large enough to create points that are far apart, especially while indoors, leading to clouds over time. You can choose the radius of your homepoint to eliminate these clouds.

## Power management

When you are near a homepoint, trkpt will slow down the GPS polling frequency to reduce power consumption. When trkpt detects movement from the device's accelerometers, or when the GPS detects you are away from the homepoint, it will wake back up to full power.

If the GPS is completely unable to receive a fix because you are indoors, underground, or trapped in a Faraday cage, trkpt will turn it off after a few minutes. Without any fix, we can't even tell if we're near a homepoint, and the GPS burns a lot of energy trying. Again, the motion sensor will wake it back up to full power.

When you are away from a homepoint, and the GPS is not struggling, trkpt will always run the GPS at full power.

If your device doesn't support the motion sensors used here, then trkpt will always run at full power. It will not sleep or kill the GPS. Maybe we can find another solution to improve battery performance for devices in this scenario.

## Mirrors

https://github.com/voussoir/trkpt

https://gitlab.com/voussoir/trkpt

https://codeberg.org/voussoir/trkpt
