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

trkpt has three states of power management. The states transition like this:

1. **FULL POWER**: receives location updates as fast as Android provides them.

    Stay near homepoint for a few minutes → Sleep

    Unable to receive fix for several minutes and not charging → Dead

2. **SLEEPING**: receives location updates at a slower pace.

    Motion sensors → Full power

    Location leaves homepoint → Full power (presumably motion sensors will trigger, but just in case)

    Charger plugged or unplugged → Full power

3. **DEAD**: disables location updates.

    Motion sensors → Full power

    Charger plugged or unplugged → Full power

Although saving battery power is important, capturing trackpoints is the #1 priority. I'd rather have too many wakeups than too few.

If your device doesn't support the motion sensors used here, then trkpt will always run at full power. It will not sleep or kill the GPS. Maybe we can find another solution to improve battery performance for devices in this scenario.

## Doze

[Doze](https://developer.android.com/training/monitoring-device-state/doze-standby) is an Android feature that saves battery power by slowing down and turning off services. When the device dozes, trkpt will stop receiving new location fixes and your track will be incomplete. Even though trkpt runs as a foreground service with a persistent notification, and requests a wakelock, and you can exempt it from battery optimization, it is still vulnerable to doze because Android implementers don't respect the user's decisionmaking capabilities.

Android exits doze during significant motion, so it does not doze while walking. However, in my experience, it will doze while driving a car, which leads to missed points on the journey.

Android never enters doze while the device is charging, so plugging it in to your car's power or an external battery is the best way to guarantee a complete track. When it comes to power banks, be aware that many models will turn themselves off once the device is fully charged and stops drawing current, and then your device may doze! An "always-on" power bank would be recommended to guarantee a complete track.

## Mirrors

https://git.voussoir.net/voussoir/trkpt

https://github.com/voussoir/trkpt

https://gitlab.com/voussoir/trkpt

https://codeberg.org/voussoir/trkpt
