package org.xbill.mDNS;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class DatagramProcessor extends NetworkProcessor
{
	private static final Logger log = LoggerFactory.getLogger(DatagramProcessor.class);

    // The default UDP datagram payload size
    protected int maxPayloadSize = 512;

    protected boolean isMulticast = false;

    protected DatagramSocket socket;

    private long lastPacket;


    public DatagramProcessor(final InetAddress ifaceAddress, final InetAddress address, final int port, final PacketListener listener)
    throws IOException
    {
        super(ifaceAddress, address, port, listener);

        if (address != null)
        {
            isMulticast = address.isMulticastAddress();
        }

        NetworkInterface netIface = null;
        if (isMulticast)
        {
            MulticastSocket socket = new MulticastSocket(port);

            // Set the IP TTL to 255, per the mDNS specification [RFC 6762].
            socket.setLoopbackMode(true);
            socket.setReuseAddress(true);
            socket.setTimeToLive(255);

            socket.setInterface(ifaceAddress);

            socket.joinGroup(address);

            this.socket = socket;
        } else
        {
            socket = new DatagramSocket(new InetSocketAddress(ifaceAddress, port));
        }

        netIface = NetworkInterface.getByInetAddress(ifaceAddress);

        // Determine maximum mDNS Payload size
        if (netIface == null)
        {
            netIface = NetworkInterface.getByInetAddress(socket.getLocalAddress());
            if (netIface == null)
            {
                InetAddress addr = socket.getInetAddress();
                if (addr != null)
                {
                    netIface = NetworkInterface.getByInetAddress(addr);
                }
            }
        }

        if (netIface != null)
        {
            try
            {
                mtu = netIface.getMTU();
            } catch (SocketException e)
            {
                netIface = null;
                log.error("Error getting MTU from Network Interface " + netIface + ".");
            }
        }

        if (netIface == null)
        {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            int smallestMtu = DEFAULT_MTU;
            while (ifaces.hasMoreElements())
            {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isLoopback() && !iface.isVirtual() && iface.isUp())
                {
                    int mtu = iface.getMTU();
                    if (mtu < smallestMtu)
                    {
                        smallestMtu = mtu;
                    }
                }
            }
            mtu = smallestMtu;
        }

        maxPayloadSize = mtu - 40 /* IPv6 Header Size */- 8 /* UDP Header */;
    }


    @Override
    public void close()
    throws IOException
    {
        super.close();

        if (isMulticast)
        {
            try
            {
                ((MulticastSocket) socket).leaveGroup(address);
            } catch (SecurityException e)
            {
                if (verboseLogging)
                {
                    log.error("Security issue leaving Multicast Group \"" + address.getAddress() + "\"", e);
                }
            } catch (Exception e)
            {
                if (verboseLogging)
                {
                    log.error("Error leaving Multicast Group \"" + address.getAddress() + "\"", e);
                }
            }
        }

        socket.close();
    }


    public int getMaxPayloadSize()
    {
        return maxPayloadSize;
    }


    public boolean isMulticast()
    {
        return isMulticast;
    }


    @Override
    public boolean isOperational()
    {
        return super.isOperational() && socket.isBound() && !socket.isClosed() && (lastPacket <= (System.currentTimeMillis() + 120000));
    }


    public void run()
    {
        lastPacket = System.currentTimeMillis();
        while (!exit)
        {
            try
            {
                byte[] buffer = new byte[mtu];
                final DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                socket.receive(datagram);
                lastPacket = System.currentTimeMillis();
                if (datagram.getLength() > 0)
                {
                    Packet packet = new Packet(datagram);
                    if (verboseLogging)
                    {
                        log.error("-----> Received packet " + packet.id + " <-----");
                        packet.timer.start();
                    }
                    processorExecutor.execute(new PacketRunner(listener, packet));
                }
            } catch (SecurityException e)
            {
                log.error("Security issue receiving data from \"" + address + "\"", e);
            } catch (Exception e)
            {
                if (!exit)
                {
                    log.error("Error receiving data from \"" + address + "\"", e);
                }
            }
        }
    }


    @Override
    public void send(final byte[] data)
    throws IOException
    {
        if (exit)
        {
            return;
        }

        DatagramPacket packet = new DatagramPacket(data, data.length, address, port);

        try
        {
            if (isMulticast)
            {
                // Set the IP TTL to 255, per the mDNS specification [RFC 6762].
                ((MulticastSocket) socket).setTimeToLive(255);
            }
            socket.send(packet);
        } catch (IOException e)
        {
            if (verboseLogging)
            {
                log.error("Error sending datagram to \"" + packet.getSocketAddress() + "\".", e);
            }

            if ("No route to host".equalsIgnoreCase(e.getMessage()))
            {
                close();
            }

            IOException ioe = new IOException("Exception \"" + e.getMessage() + "\" occured while sending datagram to \"" + packet.getSocketAddress() + "\".", e);
            ioe.setStackTrace(e.getStackTrace());
            throw ioe;
        }
    }


    @Override
    public void setAddress(final InetAddress address)
    {
        super.setAddress(address);
        isMulticast = address.isMulticastAddress();
    }


    @Override
    protected void finalize()
    throws Throwable
    {
        close();
        super.finalize();
    }
}
