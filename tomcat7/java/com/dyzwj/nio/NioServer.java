package com.dyzwj.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Scanner;

/**
 * NIO服务端
 *
 */
public class NioServer implements Runnable {

    // 通道管理器
    private Selector selector;
    private ByteBuffer readBuffer = ByteBuffer.allocate(1024);
    private ByteBuffer writeBuffer = ByteBuffer.allocate(1024);


    public NioServer(int port) {
        init(port);
    }

    /**
     * 获得一个ServerSocket通道，并对该通道做一些初始化的工作
     *
     * @param port 绑定的端口号
     */
    public void initServer(int port) throws IOException {
        init(port);
    }

    private void init(int port) {
        try {
            System.out.println("Server staring at port: " + port);
            // 开启多路复用器
            this.selector = Selector.open();
            // 开启服务通道
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(port));
            serverChannel.register(this.selector, SelectionKey.OP_ACCEPT);
            System.out.println("Server started");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 采用轮询的方式监听selector上是否有需要处理的事件，如果有，则进行处理
     *
     */
    public void run() {
        while (true) {
            try {
                this.selector.select();
                Iterator<SelectionKey> keys = this.selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();
                    if (key.isValid()) {
                        try {
                            if (key.isAcceptable()) {
                                accept(key);
                            }
                        } catch (CancelledKeyException cke) {
                            key.cancel();
                        }
                        try {
                            if (key.isReadable()) {
                                read(key);
                            }
                        } catch (CancelledKeyException cke) {
                            key.cancel();
                        }
                        try {
                            if (key.isWritable()) {
                                write(key);
                            }
                        } catch (CancelledKeyException cke) {
                            key.cancel();
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void accept(SelectionKey key) {
        try {
            ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
            SocketChannel channel = serverChannel.accept();
            channel.configureBlocking(false);
            channel.register(this.selector, SelectionKey.OP_READ);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void read(SelectionKey key) {
        try {
            this.readBuffer.clear();
            SocketChannel channel = (SocketChannel) key.channel();
            int readLength = channel.read(readBuffer);
            if (readLength == -1) {
                key.channel().close();
                key.cancel();
                return;
            }
            this.readBuffer.flip();
            byte[] datas = new byte[readBuffer.remaining()];
            readBuffer.get(datas);
            System.out
                    .println("From " + channel.getRemoteAddress() + "Clinet: " + new String(datas, "UTF-8"));
            channel.register(this.selector, SelectionKey.OP_WRITE);
        } catch (IOException e) {
            e.printStackTrace();

        }
    }

    @SuppressWarnings("resource")
    private void write(SelectionKey key) {
        this.writeBuffer.clear();
        SocketChannel channel = (SocketChannel) key.channel();
        Scanner reader = new Scanner(System.in);
        try {
            String line = reader.nextLine();
            writeBuffer.put(line.getBytes("UTF-8"));
            writeBuffer.flip();
            channel.write(writeBuffer);
            channel.register(this.selector, SelectionKey.OP_READ);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 启动服务端测试
     *
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        new Thread(new NioServer(9999)).start();
    }

}
