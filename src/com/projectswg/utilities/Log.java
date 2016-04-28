package com.projectswg.utilities;

import java.text.DateFormat;
import java.text.SimpleDateFormat;

public class Log {
	
	private static final String FORMAT_STRING = "dd-MM-yy HH:mm:ss.SSS";
	
	public static void out(String tag, String format, Object ... args) {
		DateFormat date = new SimpleDateFormat(FORMAT_STRING);
		System.out.printf("%s [%s] %s%n", date.format(System.currentTimeMillis()), tag, String.format(format, args));
	}
	
	public static void out(Object tag, String format, Object ... args) {
		out(tag.getClass().getSimpleName(), format, args);
	}
	
	public static void out(String tag, Exception e) {
		out(tag, getExceptionString(e));
	}
	
	public static void out(Object tag, Exception e) {
		out(tag.getClass().getSimpleName(), getExceptionString(e));
	}
	
	public static void err(String tag, String format, Object ... args) {
		DateFormat date = new SimpleDateFormat(FORMAT_STRING);
		System.err.printf("%s [%s] %s%n", date.format(System.currentTimeMillis()), tag, String.format(format, args));
	}
	
	public static void err(Object tag, String format, Object ... args) {
		err(tag.getClass().getSimpleName(), format, args);
	}
	
	public static void err(String tag, Throwable t) {
		err(tag, getExceptionString(t));
	}
	
	public static void err(Object tag, Throwable t) {
		err(tag.getClass().getSimpleName(), getExceptionString(t));
	}
	
	private static String getExceptionString(Throwable t) {
		StringBuilder str = new StringBuilder();
		str.append(String.format("Exception in thread\"%s\" %s: %s%n", Thread.currentThread().getName(), t.getClass().getName(), t.getMessage()));
		str.append(String.format("Caused by: %s: %s%n", t.getClass(), t.getMessage()));
		for (StackTraceElement e : t.getStackTrace()) {
			str.append("    " + e.toString() + System.lineSeparator());
		}
		return str.toString();
	}
	
}