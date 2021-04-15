package com.dyzwj.nio;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Scanner;

/**
 * NIO客户端
 *
 * @author 小路
 */
public class NioClient {

    /**
     * 启动客户端测试
     *
     */
    @SuppressWarnings("resource")
    public static void main(String[] args) throws IOException {
        InetSocketAddress remote = new InetSocketAddress("localhost", 9999);
        SocketChannel channel = null;
        ByteBuffer buffer = ByteBuffer.allocate(1024);

        try {
            channel = SocketChannel.open();
            channel.connect(remote);
            Scanner reader = new Scanner(System.in);
            while (true) {
                System.out.println("Put message send to server>");
                String line = reader.nextLine();
                if (line.equals("exit")) {
                    break;
                }
                buffer.put(line.getBytes("UTF-8"));
                buffer.flip();
                channel.write(buffer);
                buffer.clear();

                int readLength = channel.read(buffer);
                if (readLength == -1) {
                    break;
                }
                buffer.flip();
                byte[] datas = new byte[buffer.remaining()];
                buffer.get(datas);
                System.out.println("From server " + new String(datas, "UTF-8"));
                buffer.clear();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (null != channel) {
                channel.close();
            }
        }
    }
}

