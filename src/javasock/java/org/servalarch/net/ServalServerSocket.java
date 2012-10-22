/* -*- Mode: Java; tab-width: 4; indent-tabs-mode: nil; c-basic-offset: 4 -*- */
/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.servalarch.net;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketOptions;
import java.nio.channels.ServerSocketChannel;


/**
 * This class represents a server-side socket that waits for incoming client
 * connections. A {@code ServerSocket} handles the requests and sends back an
 * appropriate reply. The actual tasks that a server socket must accomplish are
 * implemented by an internal {@code SocketImpl} instance.
 */
public class ServalServerSocket {

    ServalSocketImpl impl;

    static ServalSocketImplFactory factory;

    private volatile boolean isCreated;

    private boolean isBound;

    private boolean isClosed;

    // BEGIN android-removed: we do this statically, when we start the VM.
    // static {
    //    Platform.getNetworkSystem().oneTimeInitialization(true);
    // }
    // END android-removed

    /**
     * Constructs a new {@code ServerSocket} instance which is not bound to any
     * port. The default number of pending connections may be backlogged.
     *
     * @throws IOException
     *             if an error occurs while creating the server socket.
     */
    public ServalServerSocket() throws IOException {
        impl = factory != null ? factory.createSocketImpl()
                : new ServalPlainServerSocketImpl();
    }

    /**
     * Unspecified constructor.
     *
     * Warning: this function is technically part of API#1.
     * Hiding it for API#2 broke source compatibility.
     * Removing it entirely would theoretically break binary compatibility,
     *     and would be better done with some visibility over the extent
     *     of the compatibility breakage (expected to be non-existent).
     *
     * @hide
     */
    protected ServalServerSocket(ServalSocketImpl impl) {
        this.impl = impl;
    }

    /**
     * Constructs a new {@code ServerSocket} instance bound to the nominated
     * serviceID on the localhost. The default number of pending connections may be
     * backlogged.
     *
     * @param aServiceID
     *            the serviceID to listen for connection requests on.
     * @throws IOException
     *             if an error occurs while creating the server socket.
     */
    public ServalServerSocket(ServiceID aServiceID) throws IOException {
        this(aServiceID, defaultBacklog(), null);
    }

    /**
     * Constructs a new {@code ServerSocket} instance bound to the nominated
     * serviceID on the localhost. The number of pending connections that may be
     * backlogged is specified by {@code backlog}.
     *
     * @param aServiceID
     *            the serviceID to listen for connection requests on.
     * @param backlog
     *            the number of pending connection requests, before requests
     *            will be rejected.
     * @throws IOException
     *             if an error occurs while creating the server socket.
     */
    public ServalServerSocket(ServiceID aServiceID, int backlog) throws IOException {
        this(aServiceID, backlog, null);
    }

    /**
     * Constructs a new {@code ServerSocket} instance bound to the nominated
     * local host address and port. The number of pending connections that may
     * be backlogged is specified by {@code backlog}. If {@code aport} is 0 a
     * free port is assigned to the socket.
     *
     * @param aport
     *            the port number to listen for connection requests on.
     * @param localAddr
     *            the local machine address to bind on.
     * @param backlog
     *            the number of pending connection requests, before requests
     *            will be rejected.
     * @throws IOException
     *             if an error occurs while creating the server socket.
     */
    public ServalServerSocket(ServiceID aServiceID, int backlog, InetAddress localAddr)
            throws IOException {
        super();

        if (aServiceID == null)
        	throw new IllegalArgumentException("Bad serviceID");
        
        checkListen(aServiceID, ServiceID.SERVICE_ID_MAX_BITS);
        impl = factory != null ? factory.createSocketImpl()
                : new ServalPlainServerSocketImpl();

        synchronized (this) {
            impl.create(true);
            isCreated = true;
            try {
                impl.bind(aServiceID, localAddr);
                isBound = true;
                impl.listen(backlog > 0 ? backlog : defaultBacklog());
            } catch (IOException e) {
                close();
                throw e;
            }
        }
    }

    /**
     * Waits for an incoming request and blocks until the connection is opened.
     * This method returns a socket object representing the just opened
     * connection.
     *
     * @return the connection representing socket.
     * @throws IOException
     *             if an error occurs while accepting a new connection.
     */
    public ServalSocket accept() throws IOException {
        checkClosedAndCreate(false);
        if (!isBound()) {
            throw new SocketException("Socket already bound");
        }

        ServalSocket aSocket = new ServalSocket();
        
        try {
            implAccept(aSocket);
        } catch (SecurityException e) {
            aSocket.close();
            throw e;
        } catch (IOException e) {
            aSocket.close();
            throw e;
        }
        return aSocket;
    }

    /**
     * Checks whether the server may listen for connection requests on {@code
     * aServiceID} with the prefix size {@code listenBits}. Throws an exception if the port is outside the valid range
     * {@code 0 <= listenBits <= 256 } or does not satisfy the security policy.
     *
     * @param aServiceID
     *            the candidate serviceID to listen on.
     * @param listenBits
     * 			  the size of the prefix to listen on (in number of bits).
     */
    void checkListen(ServiceID aServiceID, int listenBits) {
        if (listenBits < 0 || listenBits > ServiceID.SERVICE_ID_MAX_BITS) {
            throw new IllegalArgumentException("Bad prefix to listen to: " + 
            		listenBits);
        }
        /*
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkListen(aPort);
        }
        */
    }

    /**
     * Closes this server socket and its implementation. Any attempt to connect
     * to this socket thereafter will fail.
     *
     * @throws IOException
     *             if an error occurs while closing this socket.
     */
    public void close() throws IOException {
        isClosed = true;
        impl.close();
    }

    /**
     * Answer the default number of pending connections on a server socket. If
     * the backlog value maximum is reached, any subsequent incoming request is
     * rejected.
     *
     * @return int the default number of pending connection requests
     */
    static int defaultBacklog() {
        return 50;
    }

    /**
     * Gets the local IP address of this server socket or {@code null} if the
     * socket is unbound. This is useful for multihomed hosts.
     *
     * @return the local address of this server socket.
     */
    public InetAddress getInetAddress() {
        if (!isBound()) {
            return null;
        }
        return impl.getInetAddress();
    }

    /**
     * Gets the local serviceID of this server socket or {@code null} if the socket is
     * unbound.
     *
     * @return the local serviceID this server is listening on.
     */
    public ServiceID getLocalServiceID() {
        if (!isBound()) {
            return null;
        }
        return impl.getLocalServiceID();
    }

    /**
     * Gets the timeout period of this server socket. This is the time the
     * server will wait listening for accepted connections before exiting.
     *
     * @return the listening timeout value of this server socket.
     * @throws IOException
     *             if the option cannot be retrieved.
     */
    public synchronized int getSoTimeout() throws IOException {
        if (!isCreated) {
            synchronized (this) {
                if (!isCreated) {
                    try {
                        impl.create(true);
                    } catch (SocketException e) {
                        throw e;
                    } catch (IOException e) {
                        throw new SocketException(e.toString());
                    }
                    isCreated = true;
                }
            }
        }
        return ((Integer) impl.getOption(SocketOptions.SO_TIMEOUT)).intValue();
    }

    /**
     * Invokes the server socket implementation to accept a connection on the
     * given socket {@code aSocket}.
     *
     * @param aSocket
     *            the concrete {@code SocketImpl} to accept the connection
     *            request on.
     * @throws IOException
     *             if the connection cannot be accepted.
     */
    protected final void implAccept(ServalSocket aSocket) throws IOException {
        synchronized (this) {
            impl.accept(aSocket.impl);
            aSocket.accepted();
        }
        /*
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkAccept(aSocket.getInetAddress().getHostAddress());
        }
        */
    }

    /**
     * Sets the server socket implementation factory of this instance. This
     * method may only be invoked with sufficient security privilege and only
     * once during the application lifetime.
     *
     * @param aFactory
     *            the streaming socket factory to be used for further socket
     *            instantiations.
     * @throws IOException
     *             if the factory could not be set or is already set.
     */
    public static synchronized void setSocketFactory(ServalSocketImplFactory aFactory)
            throws IOException {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkSetFactory();
        }
        if (factory != null) {
            throw new SocketException("Factory is null");
        }
        factory = aFactory;
    }

    /**
     * Sets the timeout period of this server socket. This is the time the
     * server will wait listening for accepted connections before exiting. This
     * value must be a positive number.
     *
     * @param timeout
     *            the listening timeout value of this server socket.
     * @throws SocketException
     *             if an error occurs while setting the option.
     */
    public synchronized void setSoTimeout(int timeout) throws SocketException {
        checkClosedAndCreate(true);
        if (timeout < 0) {
            throw new IllegalArgumentException("Negative timeout");
        }
        impl.setOption(SocketOptions.SO_TIMEOUT, Integer.valueOf(timeout));
    }

    /**
     * Returns a textual representation of this server socket including the
     * address, port and the state. The port field is set to {@code 0} if there
     * is no connection to the server socket.
     *
     * @return the textual socket representation.
     */
    @Override
    public String toString() {
        StringBuilder result = new StringBuilder(64);
        result.append("ServerSocket[");
        if (!isBound()) {
            return result.append("unbound]").toString();
        }
        return result.append("ServiceID=").append(getLocalServiceID())
        		.append(",addr=")
                .append(getInetAddress().getHostName()).append("/")
                .append(getInetAddress().getHostAddress()).append("]")
                .toString();
    }

    /**
     * Binds this server socket to the given local socket address. The default
     * number of pending connections may be backlogged. If the {@code localAddr}
     * is set to {@code null} the socket will be bound to an available local
     * address on any free port of the system.
     *
     * @param localAddr
     *            the local address and port to bind on.
     * @throws IllegalArgumentException
     *             if the {@code SocketAddress} is not supported.
     * @throws IOException
     *             if the socket is already bound or a problem occurs during
     *             binding.
     */
    public void bind(ServalSocketAddress localAddr) throws IOException {
        bind(localAddr, defaultBacklog());
    }

    /**
     * Binds this server socket to the given local socket address. If the
     * {@code localAddr} is set to {@code null} the socket will be bound to an
     * available local address on any free port of the system. The value for
     * {@code backlog} must e greater than {@code 0} otherwise the default value
     * will be used.
     *
     * @param localAddr
     *            the local machine address and port to bind on.
     * @param backlog
     *            the number of pending connection requests, before requests
     *            will be rejected.
     * @throws IllegalArgumentException
     *             if the {@code SocketAddress} is not supported.
     * @throws IOException
     *             if the socket is already bound or a problem occurs during
     *             binding.
     */
    public void bind(ServalSocketAddress localAddr, int backlog) throws IOException {
        checkClosedAndCreate(true);
        if (isBound()) {
            throw new BindException("Already bound");
        }
        InetAddress addr = null;
        ServiceID serviceID = null;
        
        if (localAddr != null) {
            if (!(localAddr instanceof ServalSocketAddress)) {
                throw new IllegalArgumentException("Bad socket address type " +
                		localAddr.getClass());
            }
            ServalSocketAddress servalAddr = (ServalSocketAddress) localAddr;
            if ((serviceID = servalAddr.getServiceID()) == null) {
                throw new SocketException("No serviceID");
            }
            addr = servalAddr.getAddress();
        }
        
        if (serviceID == null)
        	throw new SocketException("Invalid serviceID");
        /*
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkListen(serviceID);
        }
		*/
        
        synchronized (this) {
            try {
                impl.bind(serviceID, addr);
                isBound = true;
                impl.listen(backlog > 0 ? backlog : defaultBacklog());
            } catch (IOException e) {
                close();
                throw e;
            }
        }
    }

    /**
     * Gets the local socket address of this server socket or {@code null} if
     * the socket is unbound. This is useful on multihomed hosts.
     *
     * @return the local socket address and port this socket is bound to.
     */
    public ServalSocketAddress getLocalSocketAddress() {
        if (!isBound()) {
            return null;
        }
        return new ServalSocketAddress(getLocalServiceID(), getInetAddress());
    }

    /**
     * Returns whether this server socket is bound to a local address and port
     * or not.
     *
     * @return {@code true} if this socket is bound, {@code false} otherwise.
     */
    public boolean isBound() {
        return isBound;
    }

    /**
     * Returns whether this server socket is closed or not.
     *
     * @return {@code true} if this socket is closed, {@code false} otherwise.
     */
    public boolean isClosed() {
        return isClosed;
    }

    /**
     * Checks whether the socket is closed, and throws an exception.
     */
    private void checkClosedAndCreate(boolean create) throws SocketException {
        if (isClosed()) {
            throw new SocketException("Socket is closed");
        }

        if (!create || isCreated) {
            return;
        }

        synchronized (this) {
            if (isCreated) {
                return;
            }
            try {
                impl.create(true);
            } catch (SocketException e) {
                throw e;
            } catch (IOException e) {
                throw new SocketException(e.toString());
            }
            isCreated = true;
        }
    }

    /**
     * Sets the value for the socket option {@code SocketOptions.SO_REUSEADDR}.
     *
     * @param reuse
     *            the socket option setting.
     * @throws SocketException
     *             if an error occurs while setting the option value.
     */
    public void setReuseAddress(boolean reuse) throws SocketException {
        checkClosedAndCreate(true);
        impl.setOption(SocketOptions.SO_REUSEADDR, reuse ? Boolean.TRUE
                : Boolean.FALSE);
    }

    /**
     * Gets the value of the socket option {@code SocketOptions.SO_REUSEADDR}.
     *
     * @return {@code true} if the option is enabled, {@code false} otherwise.
     * @throws SocketException
     *             if an error occurs while reading the option value.
     */
    public boolean getReuseAddress() throws SocketException {
        checkClosedAndCreate(true);
        return ((Boolean) impl.getOption(SocketOptions.SO_REUSEADDR))
                .booleanValue();
    }

    /**
     * Sets the server socket receive buffer size {@code
     * SocketOptions.SO_RCVBUF}.
     *
     * @param size
     *            the buffer size in bytes.
     * @throws SocketException
     *             if an error occurs while setting the size or the size is
     *             invalid.
     */
    public void setReceiveBufferSize(int size) throws SocketException {
        checkClosedAndCreate(true);
        if (size < 1) {
            throw new IllegalArgumentException("Bad size: " + size);
        }
        impl.setOption(SocketOptions.SO_RCVBUF, Integer.valueOf(size));
    }

    /**
     * Gets the value for the receive buffer size socket option {@code
     * SocketOptions.SO_RCVBUF}.
     *
     * @return the receive buffer size of this socket.
     * @throws SocketException
     *             if an error occurs while reading the option value.
     */
    public int getReceiveBufferSize() throws SocketException {
        checkClosedAndCreate(true);
        return ((Integer) impl.getOption(SocketOptions.SO_RCVBUF)).intValue();
    }

    /**
     * Gets the related channel if this instance was created by a
     * {@code ServerSocketChannel}. The current implementation returns always {@code
     * null}.
     *
     * @return the related {@code ServerSocketChannel} if any.
     */
    public ServerSocketChannel getChannel() {
        return null;
    }

    /**
     * Sets performance preferences for connection time, latency and bandwidth.
     * <p>
     * This method does currently nothing.
     *
     * @param connectionTime
     *            the value representing the importance of a short connecting
     *            time.
     * @param latency
     *            the value representing the importance of low latency.
     * @param bandwidth
     *            the value representing the importance of high bandwidth.
     */
    public void setPerformancePreferences(int connectionTime, int latency,
            int bandwidth) {
        // Our socket implementation only provide one protocol: TCP/IP, so
        // we do nothing for this method
    }
}