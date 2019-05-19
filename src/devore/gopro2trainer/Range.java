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

class Range implements Comparable<Range> {
    long start;
    long end;
    long offset;
    
    Range(long start, long end) {
        this.start = start;
        this.end = Math.max(start, end);
    }
    
    long getDuration() {
        return end - start;
    }

    @Override
    public String toString() {
        return Utils.formatDateTime(start)+" "+Utils.formatDateTime(end)+" "+offset;
    }

    @Override
    public int compareTo(Range that) {
        return Long.compare(end, that.end);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(end);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Range other = (Range) obj;
        return this.end == other.end;
    }
}
