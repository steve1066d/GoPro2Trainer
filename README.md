# GoPro2Trainer
Process GoPro videos and gps data to make a virtual ride that works with bike trainers

The purpose of this is to take GoPro videos, and along with a gpx file, come up with a 
processed gpx file and mp4 file that can be used with BKool or BigRingVR.

It has the following features:
* It is a command line tool (no gui)
* It can retrieve the gps data from a hero black mp4 file.
* It creates a script that can be run to remove the stops, and trims the video to match the gpx file.
* It also trims the gpx file to match the video, so both files start and stop at the same time.
* It can combine multiple gpx, and mp4 files, 
* It smoothes out elevation data.

It writes a shell script or batch file that calls ffmeg to do the actual copying.  You will need to install 
a recent version ffmeg if you don't already have it.  See https://ffmpeg.org/


Notes:

## Its not quite ready for prime time.

GoPro videos have a key frame every 1 second, which I use to determine where to cut the videos.
The approach I use might not work for other cameras.
