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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.UnrecognizedOptionException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOCase;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

public class VirtualRide {
    private static final Logger logger = Logger.getLogger(VirtualRide.class.getName());
    private static CommandLine cmd;
    private static File directory;
    /* Used to correct the video timing */
    private static long offset;
    private static long trimStart;
    private static long trimEnd;
    
    private final GPXHelper gpxHelper;
    private final VideoHelper vh;
    private static String baseOutputName;
    private static final ArrayList<File> sourceFiles = new ArrayList<>();
    
    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%5$s%6$s%n");
        Logger.getLogger("").setLevel(Level.INFO);
    }
    
    public VirtualRide(File directory) throws IOException, SAXException, ParserConfigurationException {
        vh = new VideoHelper(directory, cmd);
        gpxHelper = new GPXHelper(sourceFiles, baseOutputName, cmd);
        vh.setOutputFile(baseOutputName);
        vh.load(offset, gpxHelper);
    }
    
    public static void main(String[] args) throws Exception {
        long time = System.currentTimeMillis();
        args = new String[]{"-o", "Virtual Ride.gpx"};
        
        Options options = new Options();
        options.addOption("o", true, "Output file");
        options.addOption("reencode", "Reencode the output. Otherwise, the stream is copied");
        options.addOption("offset", true, "Sets the offset of the video in milliseconds");
        options.addOption("trimStart", true, "Trims the start of the file in milliseconds");
        options.addOption("trimEnd", true, "Trims the end of the file in milliseconds");
        options.addOption("maxSizeMB", true, "Sets the maximum size if reencoding.  The default is 2300");
        options.addOption("bitrate", true, "The bitrate to use, if the maxSize is not reached. The default is 10000");
        options.addOption("stopSpeed", true, "The minimum speed before a stop is recognized.  The default is 3 km/h");
        options.addOption("startSpeed", true, "The minimum speed before a stop end is recognized. The default is 6 mph");
        options.addOption("mph", "Specify the speed in mph.  Otherwise km/h is used");
        options.addOption("fixMissing", "Replaces duplicate points with the average of adjacent points.  Helpful for at least my Garmin");
        options.addOption("quiet", "Suppress info messages");
        options.addOption("verbose", "Display all messages");
        options.addOption("help", "Help");

        CommandLineParser parser = new DefaultParser();
        boolean invalid = false;
        try {
            cmd = parser.parse( options, args);
        } catch (UnrecognizedOptionException e) {
            logger.log(Level.WARNING, e.getMessage());
            invalid = true;
        }
        if (invalid || cmd.hasOption("help")) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("VirtualRide [Option]... [SourceFile]...", options);
            System.exit(invalid ? 1 :0);
        }
        if (cmd.hasOption("quiet")) {
            logger.setLevel(Level.WARNING);
        }
        if (cmd.hasOption("verbose")) {
            logger.setLevel(Level.FINEST);
        }
        
        List<String> list = cmd.getArgList();
        if (list.isEmpty()) {
            list = Collections.singletonList("./");
        }
        baseOutputName = cmd.getOptionValue("o");
        if (baseOutputName != null) {
            baseOutputName = FilenameUtils.removeExtension(baseOutputName);
        }
        for (String fileName : list) {
            File f = new File(fileName);
            if (directory == null) {
                if (f.isDirectory()) {
                    directory = f;
                } else {
                    directory = f.getParentFile();
                }
            }
            if (f.isDirectory()) {
                Collection<File> files = 
                     FileUtils.listFiles(f, new WildcardFileFilter("*.gpx", IOCase.INSENSITIVE), null);
                sourceFiles.addAll(files);
                
                String checkPath = new File(baseOutputName+".gpx").getCanonicalPath();
                for(Iterator<File> it = sourceFiles.iterator(); it.hasNext();) {
                   File file = it.next();
                   if (file.getCanonicalPath().equals(checkPath)) {
                       it.remove();
                   }
                }
                if (sourceFiles.isEmpty()) {
                    files = FileUtils.listFiles(f, new WildcardFileFilter("*.mp4", IOCase.INSENSITIVE), null);
                    sourceFiles.addAll(files);
                }
            } else if (f.isFile()) {
                sourceFiles.add(f);
            } else {
                System.err.println("Could not find "+f);
            }
        }
        if (sourceFiles.isEmpty()) {
            throw new RuntimeException("could not find any mp4 or gpx files");
        }

        offset = Long.parseLong(cmd.getOptionValue("offset", "0"));
        trimStart = Long.parseLong(cmd.getOptionValue("trimStart", "0"));
        trimEnd = Long.parseLong(cmd.getOptionValue("trimEnd", "0"));
        
        if (baseOutputName == null) {
            File f = sourceFiles.iterator().next();
            baseOutputName = FilenameUtils.removeExtension(f.getName());
            baseOutputName += "_virt";
        }
        VirtualRide vr = new VirtualRide(directory);
        vr.run();
        System.out.println("time: "+(System.currentTimeMillis() - time));
    }
    
    public void run() throws IOException, TransformerException, ParserConfigurationException {
        logger.log(Level.INFO, "start g/v {0} {1} {2}", new Object[] {
                Utils.formatDateTime(gpxHelper.startTime()), 
                Utils.formatDateTime(vh.startTime()), 
                Utils.formatElapsed(Math.abs(gpxHelper.startTime() - vh.startTime()))});
        logger.log(Level.INFO, "end   g/v {0} {1} {2}", new Object[] {
                Utils.formatDateTime(gpxHelper.endTime()), 
                Utils.formatDateTime(vh.endTime()), 
                Utils.formatElapsed(Math.abs(gpxHelper.endTime() - vh.endTime()))});

        gpxHelper.trim(vh.startTime() + trimStart, vh.endTime() - trimEnd);
        if (cmd.hasOption("fixMissing")) {
            gpxHelper.fixMissingUpdates();
        }
        gpxHelper.changePolling(1000);
        gpxHelper.markSpots();
        vh.trim(gpxHelper.startTime(), gpxHelper.endTime(), gpxHelper.getCuts());

        gpxHelper.removeStops();
        gpxHelper.validate();
        gpxHelper.smoothEleveation();
        
        writeXML(gpxHelper.getBaseName()+".gpx");
        logger.log(Level.INFO, "start g/v {0} {1} {2}", 
                new Object[]{Utils.formatDateTime(gpxHelper.startTime()), 
                    Utils.formatDateTime(vh.startTime()), 
                    Utils.formatElapsed(Math.abs(gpxHelper.startTime() - vh.startTime()))});
        logger.log(Level.INFO, "end   g/v {0} {1} {2}", 
                new Object[]{Utils.formatDateTime(gpxHelper.endTime()), 
                    Utils.formatDateTime(vh.endTime()), 
                    Utils.formatElapsed(Math.abs(gpxHelper.endTime() - vh.endTime()))});
    }
    
    public void writeXML(String fileName) throws TransformerException, IOException, ParserConfigurationException {
        ArrayList<String> srcFiles = new ArrayList<>();
        srcFiles.addAll(gpxHelper.getSourceFiles());
        srcFiles.addAll(vh.getSourceFiles());

        DocumentBuilderFactory documentFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder = documentFactory.newDocumentBuilder();
        Document doc = documentBuilder.newDocument();
        Element gpx = doc.createElement("gpx");
        gpx.setAttribute("xmlns", "http://www.topografix.com/GPX/1/1");
        gpx.setAttribute("creator", "VirtualRide by Steve Devore <mncyclist66@gmail.com>");
        doc.appendChild(gpx);
        Element metadata = doc.createElement("metadata");
        gpx.appendChild(metadata);
        Element desc = doc.createElement("desc");
        desc.setTextContent("Derived from: "+srcFiles);
        metadata.appendChild(desc);
        Element trk = doc.createElement("trk");
        gpx.appendChild(trk);
        Element trkseg = doc.createElement("trkseg");
        trk.appendChild(trkseg);
        
        gpxHelper.populate(doc, trkseg);
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        DOMSource source = new DOMSource(gpx);
        StreamResult result = new StreamResult(new File(fileName));
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.transform(source, result);
    }
}
