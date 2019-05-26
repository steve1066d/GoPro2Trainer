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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

public class Utils {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern ("MM/dd/yy HH:mm:ss.SS");

    public static String formatElapsed(long millis) {
        String timeStr = String.format("%02d:%02d:%02d",
            TimeUnit.MILLISECONDS.toHours(millis),
            TimeUnit.MILLISECONDS.toMinutes(millis) - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(millis)),
            TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))
        );
        return timeStr;
    }
    
    public static String formatDateTime(long millis) {
        Instant instant = Instant.ofEpochMilli (millis);
        ZonedDateTime zdt = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault());
        return FORMATTER.format(zdt);
    }
    
    public static String isoDate(long millis) {
        return Instant.ofEpochMilli(millis).toString();        
    }


    public static double interpolate(long x1, double y1, long x2, double y2, long x) {
        return ((x - x1) * (y2 - y1)) / (x2 - x1) + y1;
    }
}
