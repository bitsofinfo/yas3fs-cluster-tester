package org.bitsofinfo.yas3fs;

/**
 * Used by Yas3fsMountTest
 *
 */
public class Payload {

	public String sourceHost = null;
	public String type = null;
	public String data = null;
	
	public Payload(String hostname, String type, String data) {
		super();
		this.sourceHost = hostname;
		this.type = type;
		this.data = data;
	}
}
