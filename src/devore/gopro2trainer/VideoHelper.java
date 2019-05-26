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
 * Methods to load, trim, clip and combine videos.
 */

public class VideoHelper {
    private static final Logger logger = Logger.getLogger(VideoHelper.class.getName());
    public int maxSizeMB;
    public int normalBitrate;
    public static boolean reencode;
    private static final String FPS="19.98";  //29.97
    public static final String CUT = "ffmpeg -y -ss %f -i %s -t %f -avoid_negative_ts 1 -c copy -map 0:v:0 cut%03d.mp4\n";
    public static final String STREAM_COPY = "ffmpeg -y -safe 0 -f concat -i mylist.txt -c copy -map v -metadata creation_time=\"%s\" \"%s\"\n";
    private static final String ENCODE_ARGS = "%s -c:v libx264 -b:v %dk %s ";
    public static final String TWO_PASS = 
        "ffmpeg -y "+ENCODE_ARGS+" -pass 1 -f mp4 /dev/null && " +
        "ffmpeg -y "+ENCODE_ARGS+"-metadata creation_time=\"%s\" -pass 2 \"%s\"\n";
    
    public static final String SINGLE_PASS = "ffmpeg -y %s %s -metadata creation_time=\"%s\" \"%s\"\n";
    
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
    private final List<String> sourceFiles = new ArrayList<>();
    private final String filter;
    private final String encodeOptions;
    
    public VideoHelper(File dir, CommandLine cmd) {
        this.dir = dir;
        maxSizeMB = Integer.parseInt(cmd.getOptionValue("maxSizeMB", "0"));
        normalBitrate = Integer.parseInt(cmd.getOptionValue("bitrate", "0"));
        reencode = cmd.hasOption("reencode");
        filter = cmd.getOptionValue("filter");
        encodeOptions = cmd.getOptionValue("encode", "");
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
        logger.info("Load video files: ");
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
        logger.info(String.format("Loaded  Video %s to %s, duration: %s)",
                Utils.formatDateTime(startTime()),
                Utils.formatDateTime(endTime()),
                Utils.formatElapsed(endTime() - startTime())));
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
        logger.log(Level.INFO, "Trim video:   {0} to {1}, duration: {2}", new Object[]{Utils.formatDateTime(startClip), Utils.formatDateTime(endClip), Utils.formatElapsed(endClip - startClip)});

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
        logger.info("cuts");
        for (Range r : cuts) {
            logger.info(r.toString());
            dur += r.getDuration();
        }
        logger.info("includes");
        long includeDur = 0;
        for (Range r : includeRange) {
            logger.info(r.toString());
            includeDur += r.getDuration();
        }
        logger.log(Level.INFO, "cuts, includes, total: {0} + {1} = {2}", new Object[] {Utils.formatElapsed(dur), Utils.formatElapsed(includeDur), Utils.formatElapsed(dur+includeDur)});
        logger.log(Level.FINE, "r1: {0}", Utils.formatDateTime(includeRange.get(0).start));
        logger.log(Level.FINE, "r2: {0}", Utils.formatDateTime(includeRange.get(includeRange.size()-1).end));

        logger.info("Video files:");
        videoFiles.forEach((vf) -> logger.info(vf.toString()));

        StringBuilder ffmpegScript = new StringBuilder();
        StringBuilder cutFile = new StringBuilder();
        ArrayList<String> tempFiles = new ArrayList<>();
        String timestamp = Utils.isoDate(startClip);
        if (!reencode) {
            ffmpegScript.append(getStreamCuts(includeRange, cutFile, tempFiles));
            ffmpegScript.append(String.format(STREAM_COPY, timestamp, outputFile));
        } else {

            long seconds = (endClip - startClip) / 1000;
            long bitrate = normalBitrate;
            if (maxSizeMB > 0 && (seconds * bitrate / 8192) > maxSizeMB) {
                bitrate = maxSizeMB * 8192 / seconds;
            }
            String input = calcFilter(videoFiles, includeRange);
            if (bitrate > 0) {
                ffmpegScript.append(String.format(TWO_PASS, input, bitrate, encodeOptions, input, bitrate, encodeOptions, timestamp, outputFile));
                tempFiles.add("ffmpeg2pass-0.log");
                tempFiles.add("ffmpeg2pass-0.log.mbtree");
            } else {
                ffmpegScript.append(String.format(SINGLE_PASS, input, encodeOptions, timestamp, outputFile));
            }
        }
        tempFiles.forEach((tempFile) -> ffmpegScript.append(String.format("$DEL$ \"%s\"\n", tempFile)));
        
        FileUtils.writeStringToFile(new File("convert.sh"), toUnix(ffmpegScript.toString()), StandardCharsets.UTF_8.name());
        FileUtils.writeStringToFile(new File("convert.cmd"), toWindows(ffmpegScript.toString()), StandardCharsets.UTF_8.name());
        FileUtils.writeStringToFile(new File("mylist.txt"), toUnix(cutFile.toString()), StandardCharsets.UTF_8.name());
    }

    private String getStreamCuts(List<Range> includeRange, StringBuilder cutFile, List<String> tempFiles) {
        StringBuilder ffmpegScript = new StringBuilder();
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
                        // cut on keyframes.  This works for my GoPro.  Your mileage may vary.
                        if (!reencode) {
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
        if (cutNumber == 0) {
            logger.log(Level.WARNING, "There are no video cuts needed. You can use your existing mp4 as-is");
            return "";
        }
        if (cutNumber == 1 && !reencode) {
            // only 1 file, so merge isn't needed, just rename it.
            ffmpegScript.append(String.format("$MOVE$ cut001.mp4 \"%s\"", outputFile));
            tempFiles.remove("cut001.mp4");
        } else {
            tempFiles.add("mylist.txt");
        }

        logger.log(Level.INFO, "total: {0}", Utils.formatElapsed((long) (totalLength*1000)));
        return ffmpegScript.toString();
    }
    
    /**
     * This creates a custom filter that concatenates all input files, then creates nonstop segments, 
     * then combining them back to one file without stops.
     * 
     * @param videoFiles
     * @param includes
     * @return 
     */
    private String calcFilter(List<VideoFile> videoFiles, List<Range> includes) {
        StringBuilder str = new StringBuilder();
        for (VideoFile vf : videoFiles) {
            str.append("-i "+vf.getName()+" ");
        }
        str.append("-filter_complex \"");
        int ct=0;
        for (VideoFile vf : videoFiles) {
            str.append("["+ct+":v:0]");
            ct++;
        }
        str.append("concat=n="+(videoFiles.size())+":v=1[outv]; [outv]");
        str.append(" split="+includes.size()+" ");
        for (int i=0; i < includes.size(); i++) {
            str.append("[out"+i+"]");
        }
        str.append("; ");
        ct=0;
        for (Range r : includes) {
            String formatStr = 
                    String.format("start=%f:end=%f", (r.start - startClip) / 1000.0, (r.end - startClip) / 1000.0);
            str.append("[out"+ct+"]trim="+formatStr+",setpts=PTS-STARTPTS[vo"+ct+"];");
            ct++;
        }
        for (int i=0; i < ct; i++) {
            str.append("[vo"+i+"]");
        }
        str.append("concat=n="+ct+":v=1");
        if (filter != null) {
            str.append("[i];[i]"+filter);
        }
        str.append("[o]\" -map [o]");
        return str.toString();
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
