package com.stgd.voice.Util;

import java.util.zip.CRC32;

/**
 * @author Hzzz
 */
public class CRC32Util {
	public static long getCRC32(String input) {
		CRC32 crc32 = new CRC32();
		crc32.update(input.getBytes());
		return crc32.getValue();
	}
}
