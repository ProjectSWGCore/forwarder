/***********************************************************************************
* Copyright (c) 2015 /// Project SWG /// www.projectswg.com                        *
*                                                                                  *
* ProjectSWG is the first NGE emulator for Star Wars Galaxies founded on           *
* July 7th, 2011 after SOE announced the official shutdown of Star Wars Galaxies.  *
* Our goal is to create an emulator which will provide a server for players to     *
* continue playing a game similar to the one they used to play. We are basing      *
* it on the final publish of the game prior to end-game events.                    *
*                                                                                  *
* This file is part of Holocore.                                                   *
*                                                                                  *
* -------------------------------------------------------------------------------- *
*                                                                                  *
* Holocore is free software: you can redistribute it and/or modify                 *
* it under the terms of the GNU Affero General Public License as                   *
* published by the Free Software Foundation, either version 3 of the               *
* License, or (at your option) any later version.                                  *
*                                                                                  *
* Holocore is distributed in the hope that it will be useful,                      *
* but WITHOUT ANY WARRANTY; without even the implied warranty of                   *
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the                    *
* GNU Affero General Public License for more details.                              *
*                                                                                  *
* You should have received a copy of the GNU Affero General Public License         *
* along with Holocore.  If not, see <http://www.gnu.org/licenses/>.                *
*                                                                                  *
***********************************************************************************/
package com.projectswg.networking.encryption;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.projectswg.common.debug.Log;

public class MD5 {
	
	public static String digest(String text) {
		String result = text;
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			md.update(text.getBytes("iso-8859-1"), 0, text.length());
			result = convertToHex(md.digest());
		} catch (NoSuchAlgorithmException e) {
			Log.e(e);
		} catch (UnsupportedEncodingException e) {
			Log.e(e);
		}
		return result;
	}
	
	private static String convertToHex(byte[] b) {
		StringBuilder result = new StringBuilder(32);
		for (int i = 0; i < b.length; i++) {
			result.append(Integer.toString( ( b[i] & 0xff ) + 0x100, 16).substring( 1 ));
		}
		return result.toString();
	}
}