package io.niko.fmt;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

import io.niko.ktx.NetsKt;
import io.niko.utils.JavaUtil;

public abstract class AbstractBean extends Serializable {

    public String serverAddress;
    public Integer serverPort;

    public String name;

    //

    public String customOutboundJson;
    public String customConfigJson;

    // port knocking: "7000,8000/udp,9000/tcp,1234/udp:68656c6c6f"
    public String knockSequence;
    public Integer knockInterval; // ms
    public Integer knockTtl; // seconds
    public Integer knockSilence; // seconds without a reply before one re-knock, 0 off
    // TLS fragmentation: 0 off, 1 TLS records, 2 TCP segments
    public Integer tlsFragment;
    // version 4: SHA-256 of the server's public key, base64, one per line
    public String certPins;
    // version 5: DNS tunnel the profile dials through, "" off, "dnstt" or "masterdnsvpn"
    public String dnsTunnel;
    public String dnsTunnelResolver; // dnstt: https://host/dns-query, tls://host, udp://host; masterdnsvpn: IPs, one per line
    public String dnsTunnelDomain; // masterdnsvpn may take several, comma separated
    public String dnsTunnelKey; // dnstt: the server's public key; masterdnsvpn: the shared key
    public Integer dnsTunnelMethod; // masterdnsvpn DATA_ENCRYPTION_METHOD, 0-5
    public Boolean dnsTunnelFixedUpstream; // masterdnsvpn server in TCP mode instead of SOCKS5

    //
    public transient String finalAddress;
    public transient int finalPort;

    public String displayName() {
        if (JavaUtil.isNotBlank(name)) {
            return name;
        } else {
            return displayAddress();
        }
    }

    public String displayAddress() {
        return NetsKt.wrapIPV6Host(serverAddress) + ":" + serverPort;
    }

    public String network() {
        return "tcp,udp";
    }

    public boolean canICMPing() {
        return true;
    }

    public boolean canTCPing() {
        return true;
    }

    public boolean canMapping() {
        return true;
    }

    @Override
    public void initializeDefaultValues() {
        if (JavaUtil.isNullOrBlank(serverAddress)) {
            serverAddress = "127.0.0.1";
        } else if (serverAddress.startsWith("[") && serverAddress.endsWith("]")) {
            serverAddress = NetsKt.unwrapIPV6Host(serverAddress);
        }
        if (serverPort == null) {
            serverPort = 1080;
        }
        if (name == null) name = "";

        finalAddress = serverAddress;
        finalPort = serverPort;

        if (customOutboundJson == null) customOutboundJson = "";
        if (customConfigJson == null) customConfigJson = "";
        if (knockSequence == null) knockSequence = "";
        if (knockInterval == null) knockInterval = 200;
        if (knockTtl == null) knockTtl = 10;
        if (knockSilence == null) knockSilence = 15;
        if (tlsFragment == null) tlsFragment = 0;
        if (certPins == null) certPins = "";
        if (dnsTunnel == null) dnsTunnel = "";
        if (dnsTunnelResolver == null) dnsTunnelResolver = "";
        if (dnsTunnelDomain == null) dnsTunnelDomain = "";
        if (dnsTunnelKey == null) dnsTunnelKey = "";
        if (dnsTunnelMethod == null) dnsTunnelMethod = 2;
        if (dnsTunnelFixedUpstream == null) dnsTunnelFixedUpstream = false;
    }


    private transient boolean serializeWithoutName;

    @Override
    public void serializeToBuffer(@NonNull ByteBufferOutput output) {
        serialize(output);

        output.writeInt(5);
        if (!serializeWithoutName) {
            output.writeString(name);
        }
        output.writeString(customOutboundJson);
        output.writeString(customConfigJson);
        output.writeString(knockSequence);
        output.writeInt(knockInterval != null ? knockInterval : 200);
        output.writeInt(knockTtl != null ? knockTtl : 10);
        output.writeInt(tlsFragment != null ? tlsFragment : 0);
        output.writeInt(knockSilence != null ? knockSilence : 15);
        output.writeString(certPins != null ? certPins : "");
        output.writeString(dnsTunnel != null ? dnsTunnel : "");
        output.writeString(dnsTunnelResolver != null ? dnsTunnelResolver : "");
        output.writeString(dnsTunnelDomain != null ? dnsTunnelDomain : "");
        output.writeString(dnsTunnelKey != null ? dnsTunnelKey : "");
        output.writeInt(dnsTunnelMethod != null ? dnsTunnelMethod : 2);
        output.writeBoolean(dnsTunnelFixedUpstream != null && dnsTunnelFixedUpstream);
    }

    @Override
    public void deserializeFromBuffer(@NonNull ByteBufferInput input) {
        deserialize(input);

        int extraVersion = input.readInt();

        name = input.readString();
        customOutboundJson = input.readString();
        customConfigJson = input.readString();
        if (extraVersion >= 2) {
            knockSequence = input.readString();
            knockInterval = input.readInt();
            knockTtl = input.readInt();
            tlsFragment = input.readInt();
        }
        if (extraVersion >= 3) {
            knockSilence = input.readInt();
        }
        if (extraVersion >= 4) {
            certPins = input.readString();
        }
        if (extraVersion >= 5) {
            dnsTunnel = input.readString();
            dnsTunnelResolver = input.readString();
            dnsTunnelDomain = input.readString();
            dnsTunnelKey = input.readString();
            dnsTunnelMethod = input.readInt();
            dnsTunnelFixedUpstream = input.readBoolean();
        }
    }

    public void serialize(ByteBufferOutput output) {
        output.writeString(serverAddress);
        output.writeInt(serverPort);
    }

    public void deserialize(ByteBufferInput input) {
        serverAddress = input.readString();
        serverPort = input.readInt();
    }

    @NotNull
    @Override
    public abstract AbstractBean clone();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        try {
            serializeWithoutName = true;
            ((AbstractBean) o).serializeWithoutName = true;
            return Arrays.equals(KryoConverters.serialize(this), KryoConverters.serialize((AbstractBean) o));
        } finally {
            serializeWithoutName = false;
            ((AbstractBean) o).serializeWithoutName = false;
        }
    }

    @Override
    public int hashCode() {
        try {
            serializeWithoutName = true;
            return Arrays.hashCode(KryoConverters.serialize(this));
        } finally {
            serializeWithoutName = false;
        }
    }

    @NotNull
    @Override
    public String toString() {
        return getClass().getSimpleName() + " " + JavaUtil.gson.toJson(this);
    }
}
