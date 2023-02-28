/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.db.protocol.mysql.payload;

import com.google.common.base.Strings;
import io.netty.buffer.ByteBuf;
import io.netty.util.internal.StringUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.db.protocol.payload.PacketPayload;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL payload operation for MySQL packet data types.
 *
 * @see <a href="https://dev.mysql.com/doc/internals/en/describing-packets.html">describing packets</a>
 */
@Slf4j
@RequiredArgsConstructor
@Getter
public final class MySQLPacketPayload implements PacketPayload {
    
    private final ByteBuf byteBuf;
    
    private final Charset charset;
    
    /**
     * Read 1 byte fixed length integer from byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/integer.html#packet-Protocol::FixedLengthInteger">FixedLengthInteger</a>
     * 
     * @return 1 byte fixed length integer
     */
    public int readInt1() {
        return byteBuf.readUnsignedByte();
    }
    
    /**
     * Write 1 byte fixed length integer to byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/integer.html#packet-Protocol::FixedLengthInteger">FixedLengthInteger</a>
     * 
     * @param value 1 byte fixed length integer
     */
    public void writeInt1(final int value) {
        byteBuf.writeByte(value);
    }
    
    /**
     * Read 2 byte fixed length integer from byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/integer.html#packet-Protocol::FixedLengthInteger">FixedLengthInteger</a>
     *
     * @return 2 byte fixed length integer
     */
    public int readInt2() {
        return byteBuf.readUnsignedShortLE();
    }
    
    /**
     * Write 2 byte fixed length integer to byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/integer.html#packet-Protocol::FixedLengthInteger">FixedLengthInteger</a>
     *
     * @param value 2 byte fixed length integer
     */
    public void writeInt2(final int value) {
        byteBuf.writeShortLE(value);
    }
    
    /**
     * Read 3 byte fixed length integer from byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/integer.html#packet-Protocol::FixedLengthInteger">FixedLengthInteger</a>
     *
     * @return 3 byte fixed length integer
     */
    public int readInt3() {
        return byteBuf.readUnsignedMediumLE();
    }
    
    /**
     * Write 3 byte fixed length integer to byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/integer.html#packet-Protocol::FixedLengthInteger">FixedLengthInteger</a>
     *
     * @param value 3 byte fixed length integer
     */
    public void writeInt3(final int value) {
        byteBuf.writeMediumLE(value);
    }
    
    /**
     * Read 4 byte fixed length integer from byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/integer.html#packet-Protocol::FixedLengthInteger">FixedLengthInteger</a>
     *
     * @return 4 byte fixed length integer
     */
    public int readInt4() {
        return byteBuf.readIntLE();
    }
    
    /**
     * Write 4 byte fixed length integer to byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/integer.html#packet-Protocol::FixedLengthInteger">FixedLengthInteger</a>
     *
     * @param value 4 byte fixed length integer
     */
    public void writeInt4(final int value) {
        byteBuf.writeIntLE(value);
    }
    
    /**
     * Read 6 byte fixed length integer from byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/integer.html#packet-Protocol::FixedLengthInteger">FixedLengthInteger</a>
     *
     * @return 6 byte fixed length integer
     */
    public long readInt6() {
        long result = 0;
        for (int i = 0; i < 6; i++) {
            result |= ((long) (0xff & byteBuf.readByte())) << (8 * i);
        }
        return result;
    }
    
    /**
     * Write 6 byte fixed length integer to byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/integer.html#packet-Protocol::FixedLengthInteger">FixedLengthInteger</a>
     *
     * @param value 6 byte fixed length integer
     */
    public void writeInt6(final long value) {
        // TODO
    }
    
    /**
     * Read 8 byte fixed length integer from byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/integer.html#packet-Protocol::FixedLengthInteger">FixedLengthInteger</a>
     *
     * @return 8 byte fixed length integer
     */
    public long readInt8() {
        return byteBuf.readLongLE();
    }
    
    /**
     * Write 8 byte fixed length integer to byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/integer.html#packet-Protocol::FixedLengthInteger">FixedLengthInteger</a>
     *
     * @param value 8 byte fixed length integer
     */
    public void writeInt8(final long value) {
        byteBuf.writeLongLE(value);
    }
    
    /**
     * Read lenenc integer from byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/integer.html#packet-Protocol::LengthEncodedInteger">LengthEncodedInteger</a>
     *
     * @return lenenc integer
     */
    public long readIntLenenc() {
        int firstByte = readInt1();
        if (firstByte < 0xfb) {
            return firstByte;
        }
        if (0xfb == firstByte) {
            return 0;
        }
        if (0xfc == firstByte) {
            return readInt2();
        }
        if (0xfd == firstByte) {
            return readInt3();
        }
        return byteBuf.readLongLE();
    }
    
    /**
     * Write lenenc integer to byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/integer.html#packet-Protocol::LengthEncodedInteger">LengthEncodedInteger</a>
     *
     * @param value lenenc integer
     */
    public void writeIntLenenc(final long value) {
        if (value < 0xfb) {
            byteBuf.writeByte((int) value);
            return;
        }
        if (value < Math.pow(2, 16)) {
            byteBuf.writeByte(0xfc);
            byteBuf.writeShortLE((int) value);
            return;
        }
        if (value < Math.pow(2, 24)) {
            byteBuf.writeByte(0xfd);
            byteBuf.writeMediumLE((int) value);
            return;
        }
        byteBuf.writeByte(0xfe);
        byteBuf.writeLongLE(value);
    }
    
    /**
     * Read fixed length long from byte buffers.
     *
     * @param length length read from byte buffers
     *
     * @return fixed length long
     */
    public long readLong(final int length) {
        long result = 0;
        for (int i = 0; i < length; i++) {
            result = result << 8 | readInt1();
        }
        return result;
    }
    
    /**
     * Read lenenc string from byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/string.html#packet-Protocol::FixedLengthString">FixedLengthString</a>
     *
     * @return lenenc string
     */
    public String readStringLenenc() {
        int length = (int) readIntLenenc();
        byte[] result = new byte[length];
        byteBuf.readBytes(result);
        return new String(result, charset);
    }
    
    /**
     * Read lenenc string from byte buffers for bytes.
     *
     * @see <a href="https://dev.mysql.com/doc/internals/en/string.html#packet-Protocol::FixedLengthString">FixedLengthString</a>
     *
     * @return lenenc bytes
     */
    public byte[] readStringLenencByBytes() {
        int length = (int) readIntLenenc();
        byte[] result = new byte[length];
        byteBuf.readBytes(result);
        return result;
    }
    
    /**
     * Write lenenc string to byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/string.html#packet-Protocol::FixedLengthString">FixedLengthString</a>
     *
     * @param value fixed length string
     */
    public void writeStringLenenc(final String value) {
        if (Strings.isNullOrEmpty(value)) {
            byteBuf.writeByte(0);
            return;
        }
        byte[] valueBytes = value.getBytes(charset);
        writeIntLenenc(valueBytes.length);
        byteBuf.writeBytes(valueBytes);
    }
    
    /**
     * Write lenenc bytes to byte buffers.
     *
     * @param value fixed length bytes
     */
    public void writeBytesLenenc(final byte[] value) {
        if (0 == value.length) {
            byteBuf.writeByte(0);
            return;
        }
        writeIntLenenc(value.length);
        byteBuf.writeBytes(value);
    }
    
    /**
     * Read fixed length string from byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/string.html#packet-Protocol::FixedLengthString">FixedLengthString</a>
     *
     * @param length length of fixed string
     * 
     * @return fixed length string
     */
    public String readStringFix(final int length) {
        byte[] result = new byte[length];
        byteBuf.readBytes(result);
        return new String(result, charset);
    }
    
    /**
     * Read fixed length string from byte buffers and return bytes.
     *
     * @see <a href="https://dev.mysql.com/doc/internals/en/string.html#packet-Protocol::FixedLengthString">FixedLengthString</a>
     *
     * @param length length of fixed string
     *
     * @return fixed length bytes
     */
    public byte[] readStringFixByBytes(final int length) {
        byte[] result = new byte[length];
        byteBuf.readBytes(result);
        return result;
    }
    
    /**
     * Write variable length string to byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/string.html#packet-Protocol::FixedLengthString">FixedLengthString</a>
     *
     * @param value fixed length string
     */
    public void writeStringFix(final String value) {
        byteBuf.writeBytes(value.getBytes(charset));
    }
    
    /**
     * Write variable length bytes to byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/secure-password-authentication.html#packet-Authentication::Native41">Native41</a>
     *
     * @param value fixed length bytes
     */
    public void writeBytes(final byte[] value) {
        byteBuf.writeBytes(value);
    }
    
    /**
     * Read variable length string from byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/string.html#packet-Protocol::VariableLengthString">FixedLengthString</a>
     *
     * @return variable length string
     */
    public String readStringVar() {
        // TODO
        return "";
    }
    
    /**
     * Write fixed length string to byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/string.html#packet-Protocol::VariableLengthString">FixedLengthString</a>
     *
     * @param value variable length string
     */
    public void writeStringVar(final String value) {
        // TODO
    }
    
    /**
     * Read null terminated string from byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/string.html#packet-Protocol::NulTerminatedString">NulTerminatedString</a>
     *
     * @return null terminated string
     */
    public String readStringNul() {
        byte[] result = new byte[byteBuf.bytesBefore((byte) 0)];
        byteBuf.readBytes(result);
        byteBuf.skipBytes(1);
        return new String(result, charset);
    }
    
    /**
     * Read null terminated string from byte buffers and return bytes.
     *
     * @see <a href="https://dev.mysql.com/doc/internals/en/string.html#packet-Protocol::NulTerminatedString">NulTerminatedString</a>
     *
     * @return null terminated bytes
     */
    public byte[] readStringNulByBytes() {
        byte[] result = new byte[byteBuf.bytesBefore((byte) 0)];
        byteBuf.readBytes(result);
        byteBuf.skipBytes(1);
        return result;
    }
    
    /**
     * Write null terminated string to byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/string.html#packet-Protocol::NulTerminatedString">NulTerminatedString</a>
     *
     * @param value null terminated string
     */
    public void writeStringNul(final String value) {
        byteBuf.writeBytes(value.getBytes(charset));
        byteBuf.writeByte(0);
    }
    
    /**
     * Read rest of packet string from byte buffers and return bytes.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/string.html#packet-Protocol::RestOfPacketString">RestOfPacketString</a>
     *
     * @return rest of packet string bytes
     */
    public byte[] readStringEOFByBytes() {
        byte[] result = new byte[byteBuf.readableBytes()];
        byteBuf.readBytes(result);
        return result;
    }
    
    /**
     * Read rest of packet string from byte buffers.
     *
     * @see <a href="https://dev.mysql.com/doc/internals/en/string.html#packet-Protocol::RestOfPacketString">RestOfPacketString</a>
     *
     * @return rest of packet string
     */
    public String readStringEOF() {
        byte[] result = new byte[byteBuf.readableBytes()];
        byteBuf.readBytes(result);
//        return new String(result, charset);
        return readSql(result);
    }
    
    /**
     * Write rest of packet string to byte buffers.
     * 
     * @see <a href="https://dev.mysql.com/doc/internals/en/string.html#packet-Protocol::RestOfPacketString">RestOfPacketString</a>
     *
     * @param value rest of packet string
     */
    public void writeStringEOF(final String value) {
        byteBuf.writeBytes(value.getBytes(charset));
    }
    
    /**
     * Skip reserved from byte buffers.
     * 
     * @param length length of reserved
     */
    public void skipReserved(final int length) {
        byteBuf.skipBytes(length);
    }
    
    /**
     * Write null for reserved to byte buffers.
     * 
     * @param length length of reserved
     */
    public void writeReserved(final int length) {
        byteBuf.writeZero(length);
    }
    
    @Override
    public void close() {
        byteBuf.release();
    }


    /**
     * 匹配 插入/修改SQL 中含有 _binary开头的 二进制
     */
    private static final String REGEX_BINARY = "^(insert|update).*_binary'.*'.*";
    /**
     * 匹配 插入/修改SQL 中含有 x开头的 二进制
     */
    private static final String REGEX_X = "^(insert|update).*x'.*'.*";

    public String readSql(byte[] bytes) {
        try {
            if (isBinary(bytes, REGEX_BINARY)) {
                return handleBinary(bytes, REGEX_BINARY);
            } else if (isBinary(bytes, REGEX_X)) {
                return handleBinary(bytes, REGEX_X);
            } else {
                return new String(bytes, charset);
            }
        } catch (Exception e) {
            log.error("MySql handle sql package error: ", e);
            return new String(bytes, charset);
        }
    }

    /**
     * 校验是否是 INSERT UPDATE 的SQL 并且 包含二进制数据
     * @param bytes sql字节
     * @param regex 校验正则
     * @return 是否是 INSERT UPDATE 的SQL 并且 包含二进制数据
     */
    public boolean isBinary(byte[] bytes, String regex) {
        String sql = new String(bytes, charset);
        if(!StringUtil.isNullOrEmpty(sql)) {
            sql = sql.toLowerCase();
            Pattern pattern = Pattern.compile(regex, Pattern.DOTALL|Pattern.MULTILINE);
            Matcher matcher = pattern.matcher(sql);
            return matcher.matches();
        }
        return false;
    }

    public String handleBinary(byte[] bytes, String regex) {
        if(Objects.equals(regex, REGEX_BINARY)) {
            return handleBinarySql(bytes, "_binary");
        } else if(Objects.equals(regex, REGEX_X)) {
            return handleBinarySql(bytes, "x");
        } else {
            return new String(bytes, charset);
        }
    }

    public String handleBinarySql(byte[] bytes, String binaryStr) {
        String sql = new String(bytes, StandardCharsets.ISO_8859_1);
        String regex = "(?<="+ binaryStr +"').*?(?=')";
        Pattern pattern = Pattern.compile(regex, Pattern.DOTALL|Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(sql.toLowerCase());
        StringBuilder sb = new StringBuilder();
        List<String> hexList = new ArrayList<>();
        while (matcher.find()) {
            String binary = matcher.group();
            log.info("");
            log.info(" ========= binary: {}", binary);
            log.info("");
            byte[] binaryBytes = binary.getBytes(StandardCharsets.ISO_8859_1);
            String hexString = "0x" + getHexString(binaryBytes);
            matcher.appendReplacement(sb, hexString);
            hexList.add(hexString);
        }
        matcher.appendTail(sb);

        sql = sb.toString();
        // 移除_binary''
        for (String hexString : hexList) {
            int index = sql.indexOf(binaryStr + "'" + hexString + "'");
            String frontSql = sql.substring(0, index);
            String backSql = sql.substring(index + binaryStr.length() + 1).replaceFirst("'", "");
            sql = frontSql + backSql;
        }
        return new String(sql.getBytes(StandardCharsets.ISO_8859_1), charset);
    }

    public static String getHexString(byte[] bytes){
        char[] digit = { '0', '1', '2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
        char[] arr = new char[2];
        StringBuilder sb = new StringBuilder();
        for (byte mByte : bytes) {
            arr[0] = digit[(mByte>>>4)&0X0F];
            arr[1] = digit[mByte&0X0F];
            sb.append(new String(arr));
        }
        return sb.toString();
    }
}
