package com.projectswg.resources;

import java.util.prefs.Preferences;

public class HolocorePreferences {
	
	private static final HolocorePreferences INSTANCE = new HolocorePreferences();
	private static final String NODE_NAME = "/com/projectswg/SWG_Forwarder";
	
	private final Preferences pref;
	
	private HolocorePreferences() {
		pref = Preferences.userRoot().node(NODE_NAME);
	}
	
	public String getUsername() {
		return pref.get(PreferenceKeys.USERNAME.getKey(), "");
	}
	
	public void setUsername(String username) {
		pref.put(PreferenceKeys.USERNAME.getKey(), username);
	}
	
	public String getPassword() {
		return pref.get(PreferenceKeys.PASSWORD.getKey(), "");
	}
	
	public void setPassword(String username) {
		pref.put(PreferenceKeys.PASSWORD.getKey(), username);
	}
	
	public static HolocorePreferences getInstance() {
		return INSTANCE;
	}
	
	private enum PreferenceKeys {
		USERNAME	("USERNAME"),
		PASSWORD	("PASSWORD");
		
		private String key;
		
		PreferenceKeys(String key) {
			this.key = key;
		}
		
		public String getKey() {
			return key;
		}
	}
	
}
