/* -*- Mode: Java; tab-width: 4; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * The contents of this file are subject to the Netscape Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/NPL/
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express oqr
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * The Original Code is Mozilla Communicator client code, released
 * March 31, 1998.
 *
 * The Initial Developer of the Original Code is Netscape
 * Communications Corporation.  Portions created by Netscape are
 * Copyright (C) 1998 Netscape Communications Corporation. All
 * Rights Reserved.
 *
 * Contributor(s):
 *
 * Patrick C. Beard <beard@netscape.com>
 *
 * Alternatively, the contents of this file may be used under the
 * terms of the GNU Public License (the "GPL"), in which case the
 * provisions of the GPL are applicable instead of those above.
 * If you wish to allow use of your version of this file only
 * under the terms of the GPL and not to allow others to use your
 * version of this file under the NPL, indicate your decision by
 * deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL.  If you do not delete
 * the provisions above, a recipient may use your version of this
 * file under either the NPL or the GPL.
 */

import java.io.*;
import java.util.*;

class Type {
	String mName;
	int mSize;

	Type(String name, int size) {
		mName = name;
		mSize = size;
	}

	public int hashCode() {
		return mName.hashCode() + mSize;
	}

	public boolean equals(Object obj) {
		if (obj instanceof Type) {
			Type t = (Type) obj;
			return (t.mSize == mSize && t.mName.equals(mName));
		}
		return false;
	}

	public String toString() {
		return "<A HREF=\"#" + mName + "_" + mSize + "\">&LT;" + mName + "&GT;</A> (" + mSize + ")";
	}

	static class Comparator implements QuickSort.Comparator {
		public int compare(Object obj1, Object obj2) {
			Type t1 = (Type) obj1, t2 = (Type) obj2;
			return (t1.mSize - t2.mSize);
		}
	}
}

class Leak {
	String mAddress;
	Object[] mReferences;
	Object[] mCrawl;
	int mRefCount;
	Type mType;

	Leak(String addr, Type type, Object[] refs, Object[] crawl) {
		mAddress = addr;
		mReferences = refs;
		mCrawl = crawl;
		mRefCount = 0;
		mType = type;
	}

	public String toString() {
		return ("<A HREF=\"#" + mAddress + "\">" + mAddress + "</A> [" + mRefCount + "] " + mType);
	}
	
	static class ByCount implements QuickSort.Comparator {
		public int compare(Object obj1, Object obj2) {
			Leak l1 = (Leak) obj1, l2 = (Leak) obj2;
			// return (l1.mRefCount - l2.mRefCount);
			return (l1.mType.mSize - l2.mType.mSize);
		}
	}

	/**
	 * Sorts in order of decreasing size.
	 */
	static class BySize implements QuickSort.Comparator {
		public int compare(Object obj1, Object obj2) {
			Leak l1 = (Leak) obj1, l2 = (Leak) obj2;
			return (l2.mType.mSize - l1.mType.mSize);
		}
	}
}

class FileTable {
	static class Line {
		int mOffset;
		int mLength;
		
		Line(int offset, int length) {
			mOffset = offset;
			mLength = length;
		}
	}
	Line[] mLines;

	FileTable(String path) throws IOException {
		Vector lines = new Vector();
		BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(path)));
		int offset = 0;
		for (String line = reader.readLine(); line != null; line = reader.readLine()) {
			// always add 1 for the line feed.
			int length = 1 + line.length();
			lines.addElement(new Line(offset, length));
			offset += length;
		}
		reader.close();
		int size = lines.size();
		mLines = new Line[size];
		lines.copyInto(mLines);
	}
	
	public int getLine(int offset) {
		// use binary search to find the line which spans this offset.
		int length = mLines.length;
		int minIndex = 0, maxIndex = length - 1;
		int index = maxIndex / 2;
		while (minIndex <= maxIndex) {
			Line line = mLines[index];
			if (offset < line.mOffset) {
				maxIndex = (index - 1);
				index = (minIndex + maxIndex) / 2;
			} else {
				if (offset < (line.mOffset + line.mLength)) {
					return index;
				}
				minIndex = (index + 1);
				index = (minIndex + maxIndex) / 2;
			}
		}
		// this case shouldn't happen, but provides a helpful value to detect errors.
		return -1;
	}
}

public class leaksoup {
	/**
	 * Set by the "-blame" option when generating leak reports.
	 */
	static boolean USE_BLAME = false;

	public static void main(String[] args) {
		if (args.length == 0) {
			System.out.println("usage:  leaksoup leaks");
			System.exit(1);
		}
		
		String inputName = null;

		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg.charAt(0) == '-') {
				if (arg.equals("-blame"))
					USE_BLAME = true;
			} else {
				inputName = arg;
			}
		}

		try {
			Vector vec = new Vector();
			Hashtable table = new Hashtable();
			Hashtable types = new Hashtable();
			Histogram hist = new Histogram();
			BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(inputName)));
			String line = reader.readLine();
			while (line != null) {
				if (line.startsWith("0x")) {
					String addr = line.substring(0, 10);
					String name = line.substring(line.indexOf('<') + 1, line.indexOf('>'));
					int size;
					try {
						String str = line.substring(line.indexOf('(') + 1, line.indexOf(')')).trim();
						size = Integer.parseInt(str);
					} catch (NumberFormatException nfe) {
						size = 0;
					}

					// generate a unique type for this object.
					String key = name + "_" + size;
					Type type = (Type) types.get(key);
					if (type == null) {
						type = new Type(name, size);
						types.put(key, type);
					}
					
					// read in fields.
					vec.setSize(0);
					for (line = reader.readLine(); line != null && line.charAt(0) == '\t'; line = reader.readLine())
						vec.addElement(line.substring(1, 11));
					Object[] refs = new Object[vec.size()];
					vec.copyInto(refs);
					
					// read in stack crawl.
					vec.setSize(0);
					for (line = reader.readLine(); line != null && !line.equals("Leaked composite object at:"); line = reader.readLine())
						vec.addElement(line.intern());
					Object[] crawl = new Object[vec.size()];
					vec.copyInto(crawl);
					
					// record the leak.
					table.put(addr, new Leak(addr, type, refs, crawl));

					// count the leak types in a histogram.
					hist.record(type);
				} else {
					line = reader.readLine();
				}
			}
			reader.close();
			
			Leak[] leaks = new Leak[table.size()];
			int leakCount = 0;
			long totalSize = 0;

			// now, we have a table full of leaked objects, lets derive reference counts, and build the graph.
			Enumeration e = table.elements();
			while (e.hasMoreElements()) {
				Leak leak = (Leak) e.nextElement();
				Object[] refs = leak.mReferences;
				int count = refs.length;
				for (int i = 0; i < count; i++) {
					String addr = (String) refs[i];
					Leak ref = (Leak) table.get(addr);
					if (ref != null) {
						// increase the ref count.
						ref.mRefCount++;
						// change string to ref itself.
						refs[i] = ref;
					}
				}
				leaks[leakCount++] = leak;
				totalSize += leak.mType.mSize;
			}
			
			// be nice to the GC.
			table.clear();
			table = null;
			
			// store the leak report in inputName + ".html"
			PrintStream out = new PrintStream(new FileOutputStream(inputName + ".html"));

			Date now = new Date();
			out.println("<TITLE>Leaks as of " + now + "</TITLE>");

			// print leak summary.
			out.println("<H2>Leak Summary</H2>");
			out.println("total objects leaked = " + leakCount + "<BR>");
			out.println("total memory leaked  = " + totalSize + " bytes.<BR>");
			
			// print the object histogram report.
			printHistogram(out, hist);
			
			// print the leak report.
			printLeaks(out, leaks);
			
			out.close();
		} catch (Exception e) {
			e.printStackTrace(System.err);
		}
	}
	
	/**
	 * Sorts the bins of a histogram by (count * typeSize) to show the
	 * most pressing leaks.
	 */
	static class HistComparator implements QuickSort.Comparator {
		Histogram hist;
		
		HistComparator(Histogram hist) {
			this.hist = hist;
		}
	
		public int compare(Object obj1, Object obj2) {
			Type t1 = (Type) obj1, t2 = (Type) obj2;
			return (hist.count(t1) * t1.mSize - hist.count(t2) * t2.mSize);
		}
	}

	static void printHistogram(PrintStream out, Histogram hist) throws IOException {
		// sort the objects by histogram count.
		Object[] objects = hist.objects();
		QuickSort sorter = new QuickSort(new HistComparator(hist));
		sorter.sort(objects);
		
		out.println("<H2>Leak Histogram:</H2>");
		out.println("<PRE>");
		int count = objects.length;
		while (count > 0) {
			Object object = objects[--count];
			out.println(object.toString() + " : " + hist.count(object));
		}
		out.println("</PRE>");
	}

	static final String MOZILLA_BASE = "mozilla/";
	static final String LXR_BASE = "http://lxr.mozilla.org/seamonkey/source/";
	static final String BONSAI_BASE = "http://cvs-mirror.mozilla.org/webtools/bonsai/cvsblame.cgi?file=mozilla/";
	
	static String getFileLocation(Hashtable fileTables, String line) throws IOException {
		int leftBracket = line.indexOf('[');
		if (leftBracket == -1)
			return line;
		int comma = line.indexOf(',', leftBracket + 1);
		int rightBracket = line.indexOf(']', leftBracket + 1);
		String macPath = line.substring(leftBracket + 1, comma);
		String path = macPath.replace(':', '/');
		int mozillaIndex = path.indexOf(MOZILLA_BASE);
		String locationURL;
		if (mozillaIndex > -1)
			locationURL = (USE_BLAME ? BONSAI_BASE : LXR_BASE) + path.substring(path.indexOf(MOZILLA_BASE) + MOZILLA_BASE.length());
		else
			locationURL = "file:///" + path;
		int offset = 0;
		try {
			offset = Integer.parseInt(line.substring(comma + 1, rightBracket));
		} catch (NumberFormatException nfe) {
			return line;
		}
		FileTable table = (FileTable) fileTables.get(path);
		if (table == null) {
			table = new FileTable("/" + path);
			fileTables.put(path, table);
		}
		int lineNumber = 1 + table.getLine(offset);
		// return line.substring(0, leftBracket) + "[" + locationURL + "#" + lineNumber + "]";
		return "<A HREF=\"" + locationURL + "#" + lineNumber + "\"TARGET=\"SOURCE\">" + line.substring(0, leftBracket) + "</A>";
	}
	
	static void printLeaks(PrintStream out, Leak[] leaks) throws IOException {
		// sort the leaks by size.
		QuickSort bySize = new QuickSort(new Leak.BySize());
		bySize.sort(leaks);

		out.println("<H2>Leak Roots</H2>");
		
		out.println("<PRE>");

		int leakCount = leaks.length;
		for (int i = 0; i < leakCount; i++) {
			Leak leak = leaks[i];
			if (leak.mRefCount == 0)
				out.println(leak);
		}
		
		Hashtable fileTables = new Hashtable();
		Type anchorType = null;

		// now, print the report, sorted by type size.
		for (int i = 0; i < leakCount; i++) {
			Leak leak = leaks[i];
			if (anchorType != leak.mType) {
				anchorType = leak.mType;
				out.println("\n<HR>");
				out.println("<A NAME=\"" + anchorType.mName + "_" + anchorType.mSize + "\"></A>");
				out.println("<H3>" + anchorType + " Leaks</H3>");
			}
			out.println("<A NAME=\"" + leak.mAddress + "\"></A>");
			out.println(leak);
			// print object's fields:
			Object[] refs = leak.mReferences;
			int count = refs.length;
			for (int j = 0; j < count; j++)
				out.println("\t" + refs[j]);
			// print object's stack crawl:
			Object[] crawl = leak.mCrawl;
			count = crawl.length;
			for (int j = 0; j < count; j++) {
				String location = getFileLocation(fileTables, (String) crawl[j]);
				out.println(location);
			}
		}

		fileTables.clear();

		out.println("</PRE>");
	}
}
