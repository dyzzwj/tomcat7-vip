/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.buf;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;

/*
 * In a server it is very important to be able to operate on
 * the original byte[] without converting everything to chars.
 * Some protocols are ASCII only, and some allow different
 * non-UNICODE encodings. The encoding is not known beforehand,
 * and can even change during the execution of the protocol.
 * ( for example a multipart message may have parts with different
 *  encoding )
 *
 * For HTTP it is not very clear how the encoding of RequestURI
 * and mime values can be determined, but it is a great advantage
 * to be able to parse the request without converting to string.
 */

// TODO: This class could either extend ByteBuffer, or better a ByteBuffer
// inside this way it could provide the search/etc on ByteBuffer, as a helper.

/**
 * This class is used to represent a chunk of bytes, and utilities to manipulate
 * byte[].
 *
 * The buffer can be modified and used for both input and output.
 *
 * There are 2 modes: The chunk can be associated with a sink - ByteInputChannel
 * or ByteOutputChannel, which will be used when the buffer is empty (on input)
 * or filled (on output). For output, it can also grow. This operating mode is
 * selected by calling setLimit() or allocate(initial, limit) with limit != -1.
 *
 * Various search and append method are defined - similar with String and
 * StringBuffer, but operating on bytes.
 *
 * This is important because it allows processing the http headers directly on
 * the received bytes, without converting to chars and Strings until the strings
 * are needed. In addition, the charset is determined later, from headers or
 * user code.
 *
 * @author dac@sun.com
 * @author James Todd [gonzo@sun.com]
 * @author Costin Manolache
 * @author Remy Maucherat
 */
public final class ByteChunk extends AbstractChunk {

    private static final long serialVersionUID = 1L;

    /**
     * Input interface, used when the buffer is empty.
     *
     * Same as java.nio.channels.ReadableByteChannel
     */
    public static interface ByteInputChannel {

        /**
         * Read new bytes.
         *
         * @return The number of bytes read
         *
         * @throws IOException If an I/O error occurs during reading
         */
        public int realReadBytes(byte cbuf[], int off, int len) throws IOException;
    }

    /**
     * When we need more space we'll either grow the buffer ( up to the limit )
     * or send it to a channel.
     *
     * Same as java.nio.channel.WritableByteChannel.
     */
    public static interface ByteOutputChannel {

        /**
         * Send the bytes ( usually the internal conversion buffer ). Expect 8k
         * output if the buffer is full.
         *
         * @param buf bytes that will be written
         * @param off offset in the bytes array
         * @param len length that will be written
         * @throws IOException If an I/O occurs while writing the bytes
         */
        public void realWriteBytes(byte buf[], int off, int len) throws IOException;
    }

    // --------------------

    /**
     * Default encoding used to convert to strings. It should be UTF8, as most
     * standards seem to converge, but the servlet API requires 8859_1, and this
     * object is used mostly for servlets.
     */
    public static final Charset DEFAULT_CHARSET = B2CConverter.ISO_8859_1;

    private transient Charset charset;

    // byte[]
    private byte[] buff;

    // transient as serialization is primarily for values via, e.g. JMX
    //标记的请求体
    private transient ByteInputChannel in = null;
    //标记的响应体
    private transient ByteOutputChannel out = null;


    private boolean optimizedWrite = true;


    /**
     * Creates a new, uninitialized ByteChunk object.
     */
    public ByteChunk() {
    }


    public ByteChunk(int initial) {
        allocate(initial, -1);
    }


    /**
     * @deprecated Unused. Will be removed in Tomcat 8.0.x onwards.
     */
    @Deprecated
    public ByteChunk getClone() {
        try {
            return (ByteChunk) this.clone();
        } catch (Exception ex) {
            return null;
        }
    }


    private void writeObject(ObjectOutputStream oos) throws IOException {
        oos.defaultWriteObject();
        oos.writeUTF(getCharset().name());
    }


    private void readObject(ObjectInputStream ois) throws ClassNotFoundException, IOException {
        ois.defaultReadObject();
        this.charset = Charset.forName(ois.readUTF());
    }


    @Override
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }


    @Override
    public void recycle() {
        super.recycle();
        charset = null;
    }


    public void reset() {
        buff = null;
    }


    // -------------------- Setup --------------------

    public void allocate(int initial, int limit) {
        if (buff == null || buff.length < initial) {
            buff = new byte[initial];
        }
        setLimit(limit);
        start = 0;
        end = 0;
        isSet = true;
        hasHashCode = false;
    }


    /**
     * Sets the buffer to the specified subarray of bytes.
     *
     * @param b the ascii bytes
     * @param off the start offset of the bytes
     * @param len the length of the bytes
     */
    public void setBytes(byte[] b, int off, int len) {
        buff = b;
        start = off;
        end = start + len;
        isSet = true;
        hasHashCode = false;
    }


    /**
     * @deprecated Unused. Will be removed in Tomcat 8.0.x onwards.
     */
    @Deprecated
    public void setOptimizedWrite(boolean optimizedWrite) {
        this.optimizedWrite = optimizedWrite;
    }


    public void setCharset(Charset charset) {
        this.charset = charset;
    }


    public Charset getCharset() {
        if (charset == null) {
            charset = DEFAULT_CHARSET;
        }
        return charset;
    }


    /**
     * @return the buffer.
     */
    public byte[] getBytes() {
        return getBuffer();
    }


    /**
     * @return the buffer.
     */
    public byte[] getBuffer() {
        return buff;
    }


    /**
     * When the buffer is empty, read the data from the input channel.
     *
     * @param in The input channel
     */
    public void setByteInputChannel(ByteInputChannel in) {
        this.in = in;
    }


    /**
     * When the buffer is full, write the data to the output channel. Also used
     * when large amount of data is appended. If not set, the buffer will grow
     * to the limit.
     *
     * @param out The output channel
     */
    public void setByteOutputChannel(ByteOutputChannel out) {
        this.out = out;
    }


    // -------------------- Adding data to the buffer --------------------

    /**
     * Append a char, by casting it to byte. This IS NOT intended for unicode.
     *
     * @param c
     * @throws IOException
     * @deprecated Unused. Will be removed in Tomcat 8.0.x onwards.
     */
    @Deprecated
    public void append(char c) throws IOException {
        append((byte) c);
    }


    public void append(byte b) throws IOException {
        makeSpace(1);
        int limit = getLimitInternal();

        // couldn't make space
        if (end >= limit) {
            flushBuffer();
        }
        buff[end++] = b;
    }


    public void append(ByteChunk src) throws IOException {
        append(src.getBytes(), src.getStart(), src.getLength());
    }


    /**
     * Add data to the buffer.
     *
     * @param src Bytes array
     * @param off Offset
     * @param len Length
     * @throws IOException Writing overflow data to the output channel failed
     */
    public void append(byte src[], int off, int len) throws IOException {
        // will grow, up to limit
        // 向缓冲区中添加数据，需要开辟缓存区空间，缓存区初始大小为256，最大大小可以设置，默认为8192
        // 意思是现在要想缓冲区存放数据，首先得去开辟空间，但是空间是有一个最大限制的，所以要存放的数据可能小于限制，也可能大于限制
        makeSpace(len);
        int limit = getLimitInternal(); // 缓冲区大小的最大限制

        // Optimize on a common case.
        // If the buffer is empty and the source is going to fill up all the
        // space in buffer, may as well write it directly to the output,
        // and avoid an extra copy
        // 如果要添加到缓冲区中的数据大小正好等于最大限制，并且缓冲区是空的，那么则直接把数据发送给out，不要存在缓冲区中了
        if (optimizedWrite && len == limit && end == start && out != null) {
            /**
             *   如果是OutputBuffer类中调用ByteChunk.append方法，out -> OutputBuffer
             *   如果是InternalOutputBuffer类中调用ByteChunk.append方法，out -> InternalOutputBuffer
             *    OutputBuffer.realWriteBytes
             *    InternalOutputBuffer.realWriteBytes
             */
            out.realWriteBytes(src, off, len);
            return;
        }

        // if we are below the limit
        //
        /**
         * 如果要发送的数据长度小于缓冲区中剩余空间，则把数据填充到剩余空间
         * makeSpace()方法中保证了 buff.length要么是limit 要么buff.length - end >= len
         */
        if (len <= limit - end) {
            System.arraycopy(src, off, buff, end, len);
            end += len;
            return;
        }

        // 如果要发送的数据长度大于缓冲区中剩余空间，

        // Need more space than we can afford, need to flush buffer.

        // The buffer is already at (or bigger than) limit.

        // We chunk the data into slices fitting in the buffer limit, although
        // if the data is written directly if it doesn't fit.

        /**
         *  代码走到这里 此时的buffer.length 就是 limit
         * 缓冲区中还能容纳avail个字节的数据
         */

        int avail = limit - end;
        // 先将一部分数据复制到buff，填满缓冲区
        System.arraycopy(src, off, buff, end, avail);
        end += avail;

        // 将缓冲区的数据发送出去
        flushBuffer();

        // 还剩下一部分数据没有放到缓冲区中的
        int remain = len - avail;

        // 如果剩下的数据 超过 缓冲区剩余大小,那么就把数据直接发送到socketBuffer
        while (remain > (limit - end)) {
            //此时就没有复制到当前ByteChunk的buff中
            out.realWriteBytes(src, (off + len) - remain, limit - end);
            //更新剩下的数据
            remain = remain - (limit - end);
        }

        // 知道最后剩下的数据能放入缓冲区，那么就放入到缓冲区
        System.arraycopy(src, (off + len) - remain, buff, end, remain);
        end += remain;
    }


    // -------------------- Removing data from the buffer --------------------

    public int substract() throws IOException {
        if (checkEof()) {
            return -1;
        }
        return buff[start++] & 0xFF;
    }


    /**
     * @deprecated Unused. Will be removed in Tomcat 8.0.x onwards.
     */
    @Deprecated
    public int substract(ByteChunk src) throws IOException {

        if ((end - start) == 0) {
            if (in == null) {
                return -1;
            }
            int n = in.realReadBytes(buff, 0, buff.length); //
            if (n < 0) {
                return -1;
            }
        }

        int len = getLength();
        src.append(buff, start, len);
        start = end;
        return len;

    }


    public byte substractB() throws IOException {
        if (checkEof()) {
            return -1;
        }
        return buff[start++];
    }


    /**
     * 从byteChunk中读取从下表0开始 长度为len的数据到dest中
     *  刚开始ByteChunk中是没有数据的  只有调用了request.getInputStream().read(byte[])方法
     *  才会触发读取操作系统缓存的数据到ByteChunk中
     *  虽然开发人员只需要读取len长的数据到dest 但tomcat会把ByteChunk装满
     *  下次再调用request.getInputStream().read(byte[]) 就可以直接从ByteChunk中读取（赋值）
     *
     */
    public int substract(byte dest[], int off, int len) throws IOException {
        /**
         * 这里会对当前ByteChunk初始化
         * 如果ByteChunk中的数据读完了 就从操作系统缓存中读数据到InputBuffer ByteChunk是对InputBuffer数据的标记
         */
        if (checkEof()) {
            return -1;
        }
        int n = len;
        // 如果需要的数据超过buff中标记的数据长度
        if (len > getLength()) {
            n = getLength();
        }
        // 将buff数组中从start位置开始的数据，复制到dest中，长度为n，desc数组中就有值了
        System.arraycopy(buff, start, dest, off, n);
        start += n;
        return n;
    }


    private boolean checkEof() throws IOException {
        if ((end - start) == 0) {
            /**
             * start 到 end之间为可读取的数据
             * 如果end-start ==0 表示ByteChunk的数据读完了
             */

            // 如果bytechunk没有标记数据了，则开始比较
            if (in == null) {
                return true;
            }
            /**
             * 从in中读取buff长度大小的数据，读到buff中，真实读到的数据为n
             * 1、要么把ByteChunk装满
             * 2、要么把inputBuffer的数据读完
             *
             */
            int n = in.realReadBytes(buff, 0, buff.length);
            if (n < 0) {
                return true;
            }
        }
        return false;
    }


    /**
     * Send the buffer to the sink. Called by append() when the limit is
     * reached. You can also call it explicitly to force the data to be written.
     *
     * @throws IOException Writing overflow data to the output channel failed
     */
    public void flushBuffer() throws IOException {
        // assert out!=null
        if (out == null) {
            throw new IOException("Buffer overflow, no sink " + getLimit() + " " + buff.length);
        }
        //OutputBuffer.realWriteBytes
        //InternalOutputBuffer.realWriteBytes
        out.realWriteBytes(buff, start, end - start);
        end = start;
    }


    /**
     * Make space for len bytes. If len is small, allocate a reserve space too.
     * Never grow bigger than the limit or {@link AbstractChunk#ARRAY_MAX_SIZE}.
     *
     * @param count The size
     */
    public void makeSpace(int count) {
        byte[] tmp = null;

        // 缓冲区的最大大小，可以设置，默认为8192
        int limit = getLimitInternal();

        long newSize;
        // end表示缓冲区中已有数据的最后一个位置，desiredSize表示新写入数据+已有数据共多大
        long desiredSize = end + count;

        // Can't grow above the limit
        // 如果超过限制了，那就只能开辟limit大小的缓冲区了 当前数组的长度足够容纳
        //len <= limit - end
        if (desiredSize > limit) {
            desiredSize = limit;
        }

        if (buff == null) {
            // 初始化字节数组
            if (desiredSize < 256) {
                desiredSize = 256; // take a minimum
            }
            buff = new byte[(int) desiredSize];
        }

        // limit < buf.length (the buffer is already big)
        // or we already have space XXX
        // 如果需要的大小小于buff长度，那么不需要增大缓冲区
        if (desiredSize <= buff.length) {
            return;
        }

        // 下面代码的前提条件是，需要的大小超过了buff的长度

        // grow in larger chunks
        // 如果需要的大小大于buff.length, 小于2*buff.length，则缓冲区的新大小为2*buff.length，
        if (desiredSize < 2L * buff.length) {
            newSize = buff.length * 2L;
        } else {
            // 否则为buff.length * 2L + count
            newSize = buff.length * 2L + count;
        }

        // 扩容前没有超过限制，扩容后可能超过限制
        if (newSize > limit) {
            newSize = limit;
        }
        tmp = new byte[(int) newSize];

        // Compacts buffer
        // 把当前buff中的内容复制到tmp中
        System.arraycopy(buff, start, tmp, 0, end - start);
        buff = tmp;
        tmp = null;
        end = end - start;
        start = 0;
    }


    // -------------------- Conversion and getters --------------------

    @Override
    public String toString() {
        if (null == buff) {
            return null;
        } else if (end - start == 0) {
            return "";
        }
        return StringCache.toString(this);
    }


    public String toStringInternal() {
        if (charset == null) {
            charset = DEFAULT_CHARSET;
        }
        // new String(byte[], int, int, Charset) takes a defensive copy of the
        // entire byte array. This is expensive if only a small subset of the
        // bytes will be used. The code below is from Apache Harmony.
        CharBuffer cb = charset.decode(ByteBuffer.wrap(buff, start, end - start));
        return new String(cb.array(), cb.arrayOffset(), cb.length());
    }


    /**
     * @deprecated Unused. Will be removed in Tomcat 8.0.x onwards.
     */
    @Deprecated
    public int getInt() {
        return Ascii.parseInt(buff, start, end - start);
    }


    public long getLong() {
        return Ascii.parseLong(buff, start, end - start);
    }


    // -------------------- equals --------------------

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ByteChunk) {
            return equals((ByteChunk) obj);
        }
        return false;
    }


    /**
     * Compares the message bytes to the specified String object.
     *
     * @param s the String to compare
     * @return <code>true</code> if the comparison succeeded, <code>false</code>
     *         otherwise
     */
    public boolean equals(String s) {
        // XXX ENCODING - this only works if encoding is UTF8-compat
        // ( ok for tomcat, where we compare ascii - header names, etc )!!!

        byte[] b = buff;
        int len = end - start;
        if (b == null || len != s.length()) {
            return false;
        }
        int off = start;
        for (int i = 0; i < len; i++) {
            if (b[off++] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }


    /**
     * Compares the message bytes to the specified String object.
     *
     * @param s the String to compare
     * @return <code>true</code> if the comparison succeeded, <code>false</code>
     *         otherwise
     */
    public boolean equalsIgnoreCase(String s) {
        byte[] b = buff;
        int len = end - start;
        if (b == null || len != s.length()) {
            return false;
        }
        int off = start;
        for (int i = 0; i < len; i++) {
            if (Ascii.toLower(b[off++]) != Ascii.toLower(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }


    public boolean equals(ByteChunk bb) {
        return equals(bb.getBytes(), bb.getStart(), bb.getLength());
    }


    public boolean equals(byte b2[], int off2, int len2) {
        byte b1[] = buff;
        if (b1 == null && b2 == null) {
            return true;
        }

        int len = end - start;
        if (len != len2 || b1 == null || b2 == null) {
            return false;
        }

        int off1 = start;

        while (len-- > 0) {
            if (b1[off1++] != b2[off2++]) {
                return false;
            }
        }
        return true;
    }


    public boolean equals(CharChunk cc) {
        return equals(cc.getChars(), cc.getStart(), cc.getLength());
    }


    public boolean equals(char c2[], int off2, int len2) {
        // XXX works only for enc compatible with ASCII/UTF !!!
        byte b1[] = buff;
        if (c2 == null && b1 == null) {
            return true;
        }

        if (b1 == null || c2 == null || end - start != len2) {
            return false;
        }
        int off1 = start;
        int len = end - start;

        while (len-- > 0) {
            if ((char) b1[off1++] != c2[off2++]) {
                return false;
            }
        }
        return true;
    }


    /**
     * Returns true if the buffer starts with the specified string when tested
     * in a case sensitive manner.
     *
     * @param s the string
     * @deprecated Unused. Will be removed in Tomcat 8.0.x onwards.
     */
    @Deprecated
    public boolean startsWith(String s) {
        // Works only if enc==UTF
        byte[] b = buff;
        int blen = s.length();
        if (b == null || blen > end - start) {
            return false;
        }
        int boff = start;
        for (int i = 0; i < blen; i++) {
            if (b[boff++] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }


    /**
     * Returns true if the message bytes start with the specified byte array.
     *
     * @deprecated Unused. Will be removed in Tomcat 8.0.x onwards.
     */
    @Deprecated
    public boolean startsWith(byte[] b2) {
        byte[] b1 = buff;
        if (b1 == null && b2 == null) {
            return true;
        }

        int len = end - start;
        if (b1 == null || b2 == null || b2.length > len) {
            return false;
        }
        for (int i = start, j = 0; i < end && j < b2.length;) {
            if (b1[i++] != b2[j++]) {
                return false;
            }
        }
        return true;
    }


    /**
     * Returns true if the message bytes starts with the specified string.
     *
     * @param s the string
     * @param pos The position
     *
     * @return <code>true</code> if the start matches
     */
    public boolean startsWith(String s, int pos) {
        byte[] b = buff;
        int len = s.length();
        if (b == null || len + pos > end - start) {
            return false;
        }
        int off = start + pos;
        for (int i = 0; i < len; i++) {
            if (b[off++] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }


    /**
     * Returns true if the buffer starts with the specified string when tested
     * in a case insensitive manner.
     *
     * @param s the string
     * @param pos The position
     *
     * @return <code>true</code> if the start matches
     */
    public boolean startsWithIgnoreCase(String s, int pos) {
        byte[] b = buff;
        int len = s.length();
        if (b == null || len + pos > end - start) {
            return false;
        }
        int off = start + pos;
        for (int i = 0; i < len; i++) {
            if (Ascii.toLower(b[off++]) != Ascii.toLower(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }


    @Override
    protected int getBufferElement(int index) {
        return buff[index];
    }


    /**
     * @deprecated Unused. Will be removed in Tomcat 8.0.x onwards.
     */
    @Deprecated
    public int hashIgnoreCase() {
        return hashBytesIC(buff, start, end - start);
    }


    private static int hashBytesIC(byte bytes[], int start, int bytesLen) {
        int max = start + bytesLen;
        byte bb[] = bytes;
        int code = 0;
        for (int i = start; i < max; i++) {
            code = code * 37 + Ascii.toLower(bb[i]);
        }
        return code;
    }


    /**
     * Returns the first instance of the given character in this ByteChunk
     * starting at the specified byte. If the character is not found, -1 is
     * returned. <br>
     * NOTE: This only works for characters in the range 0-127.
     *
     * @param c The character
     * @param starting The start position
     * @return The position of the first instance of the character or -1 if the
     *         character is not found.
     */
    public int indexOf(char c, int starting) {
        int ret = indexOf(buff, start + starting, end, c);
        return (ret >= start) ? ret - start : -1;
    }


    /**
     * Returns the first instance of the given character in the given byte array
     * between the specified start and end. <br>
     * NOTE: This only works for characters in the range 0-127.
     *
     * @param bytes The array to search
     * @param start The point to start searching from in the array
     * @param end The point to stop searching in the array
     * @param s The character to search for
     * @return The position of the first instance of the character or -1 if the
     *         character is not found.
     */
    public static int indexOf(byte bytes[], int start, int end, char s) {
        int offset = start;

        while (offset < end) {
            byte b = bytes[offset];
            if (b == s) {
                return offset;
            }
            offset++;
        }
        return -1;
    }


    /**
     * Returns the first instance of the given byte in the byte array between
     * the specified start and end.
     *
     * @param bytes The byte array to search
     * @param start The point to start searching from in the byte array
     * @param end The point to stop searching in the byte array
     * @param b The byte to search for
     * @return The position of the first instance of the byte or -1 if the byte
     *         is not found.
     */
    public static int findByte(byte bytes[], int start, int end, byte b) {
        int offset = start;
        while (offset < end) {
            if (bytes[offset] == b) {
                return offset;
            }
            offset++;
        }
        return -1;
    }


    /**
     * Returns the first instance of any of the given bytes in the byte array
     * between the specified start and end.
     *
     * @param bytes The byte array to search
     * @param start The point to start searching from in the byte array
     * @param end The point to stop searching in the byte array
     * @param b The array of bytes to search for
     * @return The position of the first instance of the byte or -1 if the byte
     *         is not found.
     */
    public static int findBytes(byte bytes[], int start, int end, byte b[]) {
        int blen = b.length;
        int offset = start;
        while (offset < end) {
            for (int i = 0; i < blen; i++) {
                if (bytes[offset] == b[i]) {
                    return offset;
                }
            }
            offset++;
        }
        return -1;
    }


    /**
     * Returns the first instance of any byte that is not one of the given bytes
     * in the byte array between the specified start and end.
     *
     * @param bytes The byte array to search
     * @param start The point to start searching from in the byte array
     * @param end The point to stop searching in the byte array
     * @param b The list of bytes to search for
     * @return The position of the first instance a byte that is not in the list
     *         of bytes to search for or -1 if no such byte is found.
     * @deprecated Unused. Will be removed in Tomcat 8.0.x onwards.
     */
    @Deprecated
    public static int findNotBytes(byte bytes[], int start, int end, byte b[]) {
        int blen = b.length;
        int offset = start;
        boolean found;

        while (offset < end) {
            found = true;
            for (int i = 0; i < blen; i++) {
                if (bytes[offset] == b[i]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return offset;
            }
            offset++;
        }
        return -1;
    }


    /**
     * Convert specified String to a byte array. This ONLY WORKS for ascii, UTF
     * chars will be truncated.
     *
     * @param value to convert to byte array
     * @return the byte array value
     */
    public static final byte[] convertToBytes(String value) {
        byte[] result = new byte[value.length()];
        for (int i = 0; i < value.length(); i++) {
            result[i] = (byte) value.charAt(i);
        }
        return result;
    }
}
