/*
 * Copyright 2008-2017 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.mongodb.mongosql.auth.plugin;

import com.mysql.cj.exceptions.MysqlErrorNumbers;
import com.mysql.cj.protocol.AuthenticationPlugin;
import com.mysql.cj.protocol.Message;
import com.mysql.cj.jdbc.exceptions.SQLError;
import com.mysql.cj.jdbc.JdbcConnection;
import com.mysql.cj.protocol.Protocol;
import com.mysql.cj.protocol.a.NativePacketPayload;
import com.mysql.cj.util.StringUtils;



import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.mongodb.mongosql.auth.plugin.BufferHelper.writeByte;
import static org.mongodb.mongosql.auth.plugin.BufferHelper.writeBytes;
import static org.mongodb.mongosql.auth.plugin.BufferHelper.writeInt;

/**
 * A MySQL authentication plugin that implements the client-side of all MongoDB-supported authentication mechanisms.
 *
 * @since 1.0
 */
public class MongoSqlAuthenticationPlugin implements AuthenticationPlugin<NativePacketPayload> {
    private String user;
    private String password;
    private boolean firstChallenge = true;
    private String hostName;
    private String serviceName;
    private final List<SaslClient> saslClients = new ArrayList<SaslClient>();

    @Override
    public String getProtocolPluginName() {
        return "mongosql_auth";
    }

    @Override
    public boolean requiresConfidentiality() {
        return false;
    }

    @Override
    public boolean isReusable() {
        return false;
    }

    @Override
    public void setAuthenticationParameters(final String user, final String password) {
        this.user = user.contains("?") ? user.substring(0, user.indexOf("?")) : user;
        this.password = password;
        this.serviceName = findParameter("serviceName", user);
    }


    @Override
    public void init(Protocol<NativePacketPayload> protocol){
        this.hostName=protocol.getSocketConnection().getHost();
    }


    @Override
    public void destroy() {
        for (SaslClient saslClient : saslClients) {
            try {
                saslClient.dispose();
            } catch (SaslException e) {
                // ignore
            }
        }
    }

    @Override
    public boolean nextAuthenticationStep(NativePacketPayload fromServer, List<NativePacketPayload>  toServer)  {
        try {
            toServer.clear();

            if (fromServer == null) {
                return false;
//                throw SQLError.createSQLException("Unexpected empty challenge ", MysqlErrorNumbers.SQL_STATE_GENERAL_ERROR, null);
            }

            if (firstChallenge) {
                firstChallenge = false;
                toServer.add(new NativePacketPayload(new byte[0]));
                return true;
            }

            ByteBuffer byteBuffer = ByteBuffer.wrap(fromServer.getByteBuffer(), 0, fromServer.getByteBuffer().length);
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
            if (saslClients.isEmpty()) {
                String mechanism = readString(byteBuffer);
                int iterations = byteBuffer.getInt();
                for (int i = 0; i < iterations; i++) {
                    saslClients.add(createSaslClient(mechanism));
                }
            }


            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (SaslClient saslClient : saslClients) {
                byte[] response = saslClient.evaluateChallenge(getNextChallenge(byteBuffer));

                writeByte(baos, (byte) (saslClient.isComplete() ? 1 : 0));
                writeInt(baos, response.length);
                writeBytes(baos, response);
            }

            toServer.add(new NativePacketPayload(baos.toByteArray()));

            return true; // The implementation of the authentication handshake requires that this method always returns true
        } catch (SaslException e) {
            return false;
//            throw SQLError.createSQLException("mongosql_auth authentication exception ", MysqlErrorNumbers.SQL_STATE_GENERAL_ERROR, e,null);
        }catch (SQLException e1){
            return false;
        }
    }

    String getUser() {
        return user;
    }

    String getServiceName() {
        return serviceName;
    }

    private SaslClient createSaslClient(final String mechanism) throws SaslException {
        if (mechanism.equals("SCRAM-SHA-1") || mechanism.equals("SCRAM-SHA-256")) {
            return ScramSha.createSaslClient(user, password, mechanism);
        } else if (mechanism.equals("PLAIN")) {
            return Plain.createSaslClient(user, password);
        } else if (mechanism.equals("GSSAPI")) {
            return Gssapi.createSaslClient(user, hostName, serviceName);
        } else {
            throw new SaslException("Unsupported SASL mechanism " + mechanism);
        }
    }

    private String findParameter(final String target, final String search) {

        if (search.indexOf(target) <= 0
            || (search.charAt(search.indexOf(target) - 1) != '?'
                && search.charAt(search.indexOf(target) - 1) != '&')
            ) {
            return null;
        }

        int startIdx = search.indexOf(target) + target.length();

        if (startIdx >= search.length() || search.charAt(startIdx) != '=') {
            return null;
        }


        int paramStart = startIdx + 1;
        int paramEnd = -1;


        for (int i = paramStart; i < search.length(); i++) {

            if (search.charAt(i) == '&') {
                paramEnd = i;
                break;
            }
        }

        if (paramEnd == -1) {
            paramEnd = search.length();
        }

        return search.substring(paramStart, paramEnd);
    }

    private byte[] getNextChallenge(final ByteBuffer fromServer) {
        if (fromServer.remaining() == 0) {
            return new byte[0];
        }
        byte[] challengeBytes = new byte[fromServer.getInt()];
        fromServer.get(challengeBytes);
        return challengeBytes;
    }

    private String readString(final ByteBuffer byteBuffer) {
        int i = byteBuffer.position();
        int len = 0;
        int maxLen = byteBuffer.limit();

        while ((i < maxLen) && (byteBuffer.get(i) != 0)) {
            len++;
            i++;
        }

        String s = StringUtils.toString(byteBuffer.array(), byteBuffer.position(), len);
        byteBuffer.position(byteBuffer.position() + len + 1);

        return s;
    }
}
