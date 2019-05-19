/*
 * Copyright (C) 2019 Steve Devore <mncyclist66@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package devore.gopro2trainer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOCase;
import org.apache.commons.io.filefilter.WildcardFileFilter;

/**
 *
 * @author steve
 */
public class VideoHelper {
    private static final Logger logger = Logger.getLogger(VideoHelper.class.getName());
    public int maxSizeMB;
    public int normalBitrate;
    public static boolean reencode;
    
    public static final String CUT = "ffmpeg -y -ss %f -i %s -t %f -avoid_negative_ts 1 -c copy -map 0:v:0 cut%03d.mp4\n";
    public static final String STREAM_COPY = "ffmpeg -y -safe 0 -f concat -i mylist.txt -c copy -map v -metadata creation_time=\"%s\" \"%s\"\n";
    private static final String ENCODE_ARGS = "-safe 0 -f concat -i mylist.txt -map v -filter:v fps=fps=29.97 -c:v libx264 -b:v %dk ";
    public static final String ENCODE = 
        "ffmpeg -y "+ENCODE_ARGS+" -pass 1 -an -f mp4 /dev/null && " +
        "ffmpeg -y "+ENCODE_ARGS+"-metadata creation_time=\"%s\" -pass 2 \"%s\"";

    
    // public static final String GET_METADATA = "ffprobe -i \"%s\" -show_entries format=duration -show_entries format_tags=creation_time,firmware -v quiet -of csv=\"p=0\"";
    // use to determine keyframes:
    // fprobe -loglevel error -skip_frame nokey -select_streams v:0 -show_entries frame=pkt_pts_time -of csv=print_section=0 cut002.mp4

    // length
    // for %a in (cut*.mp4) do ffprobe -i %a -show_entries format=duration -show_entries format_tags=creation_time,firmware -v quiet -of csv="p=0"

    // extract gps from gopro
    // private static final String EXTRACT = "ffmpeg -i \"%s\" -codec copy -map 0:3 -f rawvideo \"%s\".gpmf";
    // private static final String TO_GPX = "gopro2gpx -i \"%s.gpmf\" -o \"%s.gpx\"";
    

    private final File dir;
    private final List<VideoFile> videoFiles = new ArrayList<>();
    private long startClip;
    private long endClip;
    private String outputFile;
    private boolean isGopro;
    private final List<String> sourceFiles = new ArrayList<>();
    
    public VideoHelper(File dir, CommandLine cmd) {
        this.dir = dir;
        maxSizeMB = Integer.parseInt(cmd.getOptionValue("maxSizeMB", "2300"));
        normalBitrate = Integer.parseInt(cmd.getOptionValue("bitrate", "10000"));
        reencode = cmd.hasOption("reencode");
    }
    
    public void setOutputFile(String outputFile) {
        this.outputFile = outputFile+".mp4";
    }

    public long startTime() {
        return videoFiles.get(0).timeStamp;
    }
    
    public long endTime() {
        VideoFile vf = videoFiles.get(videoFiles.size()-1);
        return vf.timeStamp + vf.length;
    }
    
    public void load(long offset, GPXHelper gpx) throws IOException {
        Collection<File> files = FileUtils.listFiles(dir, new WildcardFileFilter("GH*.mp4", IOCase.INSENSITIVE), null);
        for (File file : files) {
            sourceFiles.add(file.getName());
            GoProMP4 mp4 = new GoProMP4(file);
            VideoFile vf = new VideoFile(file, mp4.getTimestamp() + offset, mp4.getDuration());
            videoFiles.add(vf);
        }
        videoFiles.sort(null);
        
        // adjust start times for videos that roll over to a new file.
        long lastEndTime = 0;
        for (VideoFile vf : videoFiles) {
            if (lastEndTime > 0) {
                long delta = lastEndTime - vf.timeStamp;
                if (Math.abs(delta) < 2000) {
                    vf.timeStamp = lastEndTime;
                }
            }
            lastEndTime = vf.timeStamp + vf.length;
        }
    }
    
    /**
     * This will check for missing video, and if it finds any, it will
     * remove any gps data for that period.
     * @param cuts 
     */
    void checkForMissingVideo(List<Range> cuts) {
        // Not implemented.
    }

    void trim(long startTime, long endTime, List<Range> cuts) throws IOException {
        logger.info("starting trim");
        // If it is within a second, don't clip
        this.startClip =Math.max(startTime, startTime());
        this.endClip = Math.min(endTime, endTime());
        logger.log(Level.INFO, "bounds: {0} {1} {2}", new Object[]{Utils.formatDateTime(startClip), Utils.formatDateTime(endClip), Utils.formatElapsed(endClip - startClip)});

        // Take the list of clipped sections and calculate the 
        // the sections to save
        ArrayList<Range> includeRange = new ArrayList<>();
        long startInclude = startClip;
        for (Range r : cuts) {
            Range newR = new Range(startInclude, Math.min(r.start, endClip));
            if (newR.getDuration() > 0) {
                includeRange.add(newR);
                startInclude = r.end;
            }
        }
        Range newR = new Range(startInclude, endClip);
        if (newR.getDuration() > 0) {
            includeRange.add(newR);
        }
        
        long dur = 0;
        for (Range r : cuts) {
            logger.fine(r.toString());
            dur += r.getDuration();
        }
        logger.log(Level.INFO, "cuts: {0}", Utils.formatElapsed(dur));
        dur = 0;
        for (Range r : includeRange) {
            logger.fine(r.toString());
            dur += r.getDuration();
        }
        logger.log(Level.INFO, "dur: {0}", Utils.formatElapsed(dur));
        logger.log(Level.FINE, "r1: {0}", Utils.formatDateTime(includeRange.get(0).start));
        logger.log(Level.FINE, "r2: {0}", Utils.formatDateTime(includeRange.get(includeRange.size()-1).end));
        

        videoFiles.forEach((vf) -> logger.info(vf.toString()));

        StringBuilder ffmpegScript = new StringBuilder();
        StringBuilder cutFile = new StringBuilder();
        ArrayList<String> tempFiles = new ArrayList<>();
        
        int cutNumber = 0;
        double totalLength = 0;
        double offset =0;
        for (Range r : includeRange) {
            logger.log(Level.INFO, "include: {0}", r);
            long currentPos = r.start;
            
            // One thing that isn't perfect is that if the length is between frames, it always goes a bit longer
            // to catch a partial frame.  It might be better to go to the nearest frame, and also keep track of
            // the actual frame length, and account for the slight differences.  However, unless there are hundreds
            // of clips there should only be a sub-second difference, so I'm not bothering.
            for (VideoFile vf : videoFiles) {
                if (vf.timeStamp <= currentPos && vf.getEnding() >= currentPos ) {
                    long endPos = Math.min(vf.getEnding(), r.end);
                    double start = (currentPos - vf.timeStamp) / 1000.0;
                    double end = (endPos - vf.timeStamp) / 1000.0;
                    double length = end - start;
                    cutNumber++;
                    if (start == 0 && (endPos - vf.timeStamp) == vf.length) {
                        // We can include the entire file.
                        cutFile.append(String.format("file '%s'\n", vf.file.getPath()));
                    } else {
                        double adjStart = start + offset;
                        length -= offset;
                        if (adjStart < 0) {
                            offset = adjStart;
                            adjStart = 0;
                        } else {
                            offset = 0;
                        }
                        if (isGopro) {
                            double lenSeconds = vf.length / 1000.0;
                            // this will cut on keyframes, at least for my gopro
                            adjStart = Math.rint(adjStart / 1.001) * 1.001;
                            // if the adjustment causes the duration to be longer than the existing file,
                            // account for the new length
                            if ((adjStart + length) > lenSeconds) {
                                offset += lenSeconds - (adjStart + length);
                                logger.log(Level.FINEST, "old: {0} {1} {2}{3}", new Object[]{length, lenSeconds, adjStart, length});
                                length = length + offset;
                                logger.log(Level.FINEST, "new: {0}", length);
                            }
                        }
                        // copy the segment needed, video only
                        ffmpegScript.append(String.format(CUT, adjStart, vf.file.getPath(), length, cutNumber));
                        cutFile.append(String.format("file 'cut%03d.mp4'\n",cutNumber));
                        tempFiles.add(String.format("cut%03d.mp4", cutNumber));
                    }
                    totalLength +=length;
                    currentPos = endPos;
                }
            }
        }
        logger.log(Level.INFO, "total: {0}", Utils.formatElapsed((long) (totalLength*1000)));
        if (cutNumber == 0) {
            logger.info("There are no video cuts needed");
            return;
        }
        if (cutNumber == 1 && !reencode) {
            // only 1 file, so merge isn't needed, just rename it.
            ffmpegScript.append(String.format("$MOVE$ cut001.mp4 \"%s\"", outputFile));
            tempFiles.remove("cut001.mp4");
        } else {
            tempFiles.add("mylist.txt");
            // concatenate all the videos together
            String timestamp = Utils.isoDate(startClip);
            if (!reencode) {
                ffmpegScript.append(String.format(STREAM_COPY, timestamp, outputFile));
            } else {
                long seconds = (endClip - startClip) / 1000;
                long bitrate = normalBitrate;
                if ((seconds * bitrate / 8192) > maxSizeMB) {
                    bitrate = maxSizeMB * 8192 / seconds;
                }
                ffmpegScript.append(String.format(ENCODE, bitrate, bitrate, timestamp, outputFile));
                tempFiles.add("ffmpeg2pass-0.log");
                tempFiles.add("ffmpeg2pass-0.log.mbtree");
            }
        }
        tempFiles.forEach((tempFile) -> ffmpegScript.append(String.format("$DEL$ \"%s\"\n", tempFile)));
        
        FileUtils.writeStringToFile(new File("convert.sh"), toUnix(ffmpegScript.toString()), StandardCharsets.UTF_8.name());
        FileUtils.writeStringToFile(new File("convert.cmd"), toWindows(ffmpegScript.toString()), StandardCharsets.UTF_8.name());
        FileUtils.writeStringToFile(new File("mylist.txt"), toUnix(cutFile.toString()), StandardCharsets.UTF_8.name());
    }
    
    private String toWindows(String x) {
        return ("@echo off\n"+x).replace("/dev/null", "NUL").replace("/", "\\").replace("$MOVE$", "ren")
                .replace("$DEL$", "del").replace("\n","\r\n");
    }
    
    private String toUnix(String x) {
        return x.replace("\\", "/").replace("$MOVE$", "mv").replace("$DEL$", "rm");
    }

    public List<? extends String> getSourceFiles() {
        return sourceFiles;
    }
}
