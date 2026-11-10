package pl.ispinalokalnie.kindlewifi;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import java.io.IOException;

final class KindleConnection {
    static Session open(JSch ssh, SshIdentity sshIdentity, String host,
                        KindleFinder.Match match, KindleFinder.Pin pin,
                        boolean useKey, UserInfo user, int timeout) throws Exception {
        Session session = ssh.getSession("root", host, KindleFinder.PORT);
        if (useKey) {
            sshIdentity.addTo(ssh);
            session.setConfig("PreferredAuthentications", "publickey");
            session.setConfig("PubkeyAcceptedAlgorithms", KindleFinder.hostAlgorithms("ssh-rsa"));
        } else {
            session.setPassword("root");
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        }
        session.setConfig("StrictHostKeyChecking", "ask");
        String hostKeys = session.getConfig("server_host_key");
        if (!java.util.Arrays.asList(hostKeys.split(",")).contains("ssh-rsa")) {
            session.setConfig("server_host_key", hostKeys + ",ssh-rsa");
        }
        if (pin != null) {
            session.setHostKeyRepository(pin);
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("server_host_key", KindleFinder.hostAlgorithms(pin.type));
            if (match != null && host.equals(match.host)) session.setSocketFactory(match.sockets(6000));
        }
        if (user != null) session.setUserInfo(user);
        else if (pin == null) throw new IOException("Kindle nie jest sparowany.");


        try {
            session.connect(timeout);
            session.setTimeout(timeout == 12000 ? 30000 : timeout);
            return session;
        } catch (Exception e) { session.disconnect(); throw e; }
    }
}
