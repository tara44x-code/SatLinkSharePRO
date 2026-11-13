package com.satlink.sharepro;

public class DeviceInfo {
    public final String name;
    public final String hostIp;
    public final int port;

    public DeviceInfo(String name, String hostIp, int port) {
        this.name = name == null ? "SatLink Device" : name;
        this.hostIp = hostIp == null ? "" : hostIp;
        this.port = port;
    }

    public String key() {
        return hostIp + ":" + port;
    }
}
