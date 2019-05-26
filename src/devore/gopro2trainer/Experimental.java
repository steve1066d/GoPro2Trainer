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

import java.awt.geom.Point2D;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.commons.cli.CommandLine;
import org.xml.sax.SAXException;

/**
 * This contains functions I'm using to look at data, or are otherwise experimenting with
 * 
 * @author Steve Devore <mncyclist66@gmail.com>
 */
public class Experimental {
    public static void makeCSV(List<Point> points) throws IOException {
        File f = new File ("ride2.csv");
        try (FileWriter fw = new FileWriter(f);
             PrintWriter pw = new PrintWriter(fw))
        {
            pw.println("time, mph, slope, est power, actual power");
            for (int i=2; i < points.size(); i++) {
                Point pt = points.get(i);
                Point last = points.get(i-1);
                pw.format("%s, %f, %f, %.1f, %.1f\n", 
                        Utils.formatDateTime(pt.timestamp), 
                        pt.getMPH(last), 
                        pt.getSlope(last), 
                        getEstimatedPower(pt, last, points.get(i-2)),
                        pt.power);
            }
        }
    }
    
    static void checkSync(CommandLine cmd, List<Point> masterList) throws IOException, SAXException, ParserConfigurationException {
        String elevation = cmd.getOptionValue("elevation");
        if (elevation != null) {
            File elevationDir = new File(elevation);
            List<File> elevationList = Collections.singletonList(elevationDir);
            GPXHelper elevationHelper = new GPXHelper(elevationList, null, cmd);
            List<Point> points = elevationHelper.getPoints();
            int limit = masterList.size()-120;
            long delta = 0;
            long deltaCt = 0;
            for (int i=120; i < limit; i++) {
                Point pt = masterList.get(i);
                double closestSq = Double.MAX_VALUE;
                Point closestPt = null;
                for (int j=0; j < points.size(); j++) {
                    Point testPt = points.get(j);
                    double test = Point2D.distanceSq(pt.lat, pt.lon, testPt.lat, testPt.lon);
                    if (test < closestSq) {
                        closestSq = test;
                        closestPt = testPt;
                    }
                }
                pt.elevation = closestPt.elevation;
                delta += (pt.timestamp - closestPt.timestamp);
                deltaCt++;
            };
            System.out.println("milliseconds gopro was behind garmin: "+((double) -delta / deltaCt));
            // last run was 793
        }
    }

    private static double m = 105; // weight of bike and rider in kg
    private static final double g = 9.80655; // gravitational constant
    private static double Crr = .0050;  //(rolling resistance .002 concrete, .005, asphalt);
    private static double w = 0; // windspeed (m/s)
    // I'm making a wild guess at flat bar gravel bike resistance at .5
    private static double CdA = .5; // tops=.408, hoods=.324, drops=.307, aerobars=.2914
    private static double loss = .035; // 3% new well oiled chain, 4% dry, 5% old, dry

    // See https://www.omnicalculator.com/sports/cycling-wattage
    // I'm hoping to improve the accuracy of the elevation data.. It seems to be delayed
    // If I compare my actual power data this estimated one, I perhaps can tell if 
    // the peaks are occuring at the right time.
    public static double getEstimatedPower(Point pt, Point lastPoint, Point prevPoint) {
        double slope = pt.getSlope(lastPoint);
        double v = pt.getMPH(lastPoint) / Point.MS_TO_MPH; // in m/s
        double oldV = lastPoint.getMPH(prevPoint) / Point.MS_TO_MPH;
        double deltaV = v - oldV;
        double s = (pt.timestamp - lastPoint.timestamp) / 1000.0;
        if (v == 0) {
            return 0;
        }
        double h = pt.elevation;
        double rho = 1.225 * Math.exp(-.00011856 * h);
        double Fg = g * Math.sin(Math.atan(slope)) * m;  //gravity
        double Fr = g * Math.cos(Math.atan(slope)) * m * Crr; // road resistance
        double Fa = .5 * CdA * rho * Math.pow(v + w, 2); // air reistance
        double Facc = m * deltaV * deltaV / s / 2;// work due to accelleration, deceleration
        double P = (Fg + Fr + Fa + Facc) * v / (1 - loss);
        return P;
    }
}
