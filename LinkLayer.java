package wifi;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;

//import com.sun.xml.internal.ws.policy.privateutil.PolicyUtils.Rfc2396;

import rf.RF;

/**
 * This layer is an implementation of {@link Dot11Interface} using the patented BradCo RF layer.
 * 
 * @author Braude, Corpron, Richards
 */
public class LinkLayer implements Dot11Interface 
{
	private static final int queue_size = 4;
	private static final int ack_size = 2;
	
	private static ArrayBlockingQueue<Packet> received = new ArrayBlockingQueue<>(queue_size);
	private static ArrayBlockingQueue<Packet> outgoingQueue = new ArrayBlockingQueue<>(queue_size);
	private static ArrayBlockingQueue<Packet> ackQueue = new ArrayBlockingQueue<>(ack_size);
	private HashMap<Short, Integer> outgoingSeq = new HashMap<>();
	
	private RF theRF;           // You'll need one of these eventually
	private short ourMAC;       // Our MAC address
	private PrintWriter output; // The output stream we'll write to
	
	private static int debugLevel = 1;
	private static int slotSelection = 0;
	private static int beaconInterval = 8000; //ms
	private static int status = 0;
	private static long offset = 0;
	
	//Settings for slot selection
	public static final int SS_RANDOM = 0;
	public static final int SS_MAX = 1;
	
	//Status codes
	public static final int STATUS_SUCCESS = 1;
	public static final int STATUS_UNSPECIFIED_ERROR = 2;
	public static final int STATUS_RF_INIT_FAILED = 3;
	public static final int STATUS_TX_DELIVERED = 4;
	public static final int STATUS_TX_FAILED = 5;
	public static final int STATUS_BAD_BUF_SIZE = 6;
	public static final int STATUS_BAD_ADDRESS = 7;
	public static final int STATUS_BAD_MAC_ADDRESS = 8;
	public static final int STATUS_ILLEGAL_ARGUMENT = 9;
	public static final int STATUS_INSUFFICIENT_BUFFER_SPACE = 10;

	

	private Thread read;
	private Thread writer;

	/**
	 * Constructor takes a MAC address and the PrintWriter to which our output will
	 * be written.
	 * @param ourMAC  MAC address
	 * @param output  Output stream associated with GUI
	 */
	public LinkLayer(short ourMAC, PrintWriter output) {
		this.ourMAC = ourMAC;
		this.output = output;
		if (ourMAC == -1) throw new IllegalArgumentException("MAC Address cannot be the broadcast address (-1)!");

		try {
            theRF = new RF(null, null);
        } catch (Exception e) {
            if (debugLevel > 0) output.println("LinkLayer: Error in Making the RF layer");
		    LinkLayer.setStatus(STATUS_RF_INIT_FAILED);
        }

		if (debugLevel>0) output.println("LinkLayer initialized.");
        LinkLayer.setStatus(STATUS_SUCCESS);
		output.println("Send command 0 for a list of commands");

		// Launch threads
		Receiver rec = new Receiver(theRF, ourMAC, output, received, ackQueue);
		Sender writ = new Sender(theRF, ourMAC, output, outgoingQueue, ackQueue);

		read = new Thread(rec);
		writer = new Thread(writ);
		read.start();
		writer.start();
	}

	/**
	 * Send method takes a destination, a buffer (array) of data, and the number
	 * of bytes to send.  See docs for full description.
	 */
	public int send(short dest, byte[] data, int len) {
		if (debugLevel > 0) output.println("LinkLayer: Sending "+len+" bytes to "+dest);
		if (outgoingQueue.size() >= queue_size) {
			if (debugLevel == 4) output.println("LinkLayer: rejected transmission, too many in queue.");
			setStatus(STATUS_INSUFFICIENT_BUFFER_SPACE);
			return 0;
		}
		
		if (!outgoingSeq.containsKey(dest)) {
			outgoingSeq.put(dest, 0);
			if (debugLevel == 3) output.println("LinkLayer: new destination. Starting sequence at 0.");
		} else {
			if (debugLevel == 3) output.println("LinkLayer: sequence number is " + outgoingSeq.get(dest));
		}
		int seq = outgoingSeq.get(dest);
		outgoingSeq.put(dest, seq+1);
		byte[] acceptedData = data;
		if (data.length > Packet.MAX_DATA) {
			acceptedData = new byte[Packet.MAX_DATA];
			System.arraycopy(data, 0, acceptedData, 0, Packet.MAX_DATA);
		}
		// construct packet from dest, data, source is our mac address
		Packet p = new Packet(ourMAC, dest, acceptedData, Packet.FT_DATA, seq, false);
		outgoingQueue.add(p);

		return Math.min(len, data.length);
	}

	/**
	 * Recv method blocks until data arrives, then writes it an address info into
	 * the Transmission object.  See docs for full description.
	 */
	public int recv(Transmission t) {
		if (debugLevel == 4) output.println("LinkLayer: blocking on recv()");
		if (t == null) {
			setStatus(STATUS_BAD_ADDRESS);
			return -1;
		}
		// Block until we receive the data meant for us
		Packet incoming;
		try {
			incoming = received.take();
		} catch (Exception e) {
			if (debugLevel > 0) output.println("Didn't receive a packet, or ran into an error");
            LinkLayer.setStatus(STATUS_UNSPECIFIED_ERROR);
			return -1;
		}

		try {
			//give the transmission the necessary information
			t.setSourceAddr(incoming.getSrc());
			t.setDestAddr(incoming.getDest());

			int l = t.getBuf().length;
			byte[] data = incoming.getData();

			// Copies data to t, while respecting t buff size
            t.setBuf(Arrays.copyOf(data, l));

            //As per the specification, the remaining data is discarded
		} catch (Exception e) {
			if (debugLevel > 0) output.println("LinkLayer: Error when copying data");
            LinkLayer.setStatus(STATUS_UNSPECIFIED_ERROR);
			return -1;
		}

		//Returns the amount of data stored
		return Math.min(incoming.getData().length, t.getBuf().length);
	}

	/**
	 * Returns a current status code.  See docs for full description.
	 */
	public int status() {
	    if (status < 1 || status > 10) {
            if (debugLevel > 0) output.println("LinkLayer: Status is an illegal value");
            setStatus(STATUS_UNSPECIFIED_ERROR);
        }
	    return status;
	}

	/**
	 * Passes command info to your link layer.  See docs for full description.
	 */
	public int command(int cmd, int val) {
		if (debugLevel == 4) output.println("LinkLayer: Sending command "+cmd+" with value "+val);
		if (cmd == 0) {
			output.println("Status: " + LinkLayer.status);
			output.println("Debug level: " + LinkLayer.debugLevel);
			if (slotSelection == SS_RANDOM) {
				output.println("Slot selection is random.");
			} else {
				output.println("Slot selection is fixed.");
			}
			if (beaconInterval > 0) {
				output.println("Sending beacons every "+ beaconInterval/1000 + " seconds");
			} else {
				output.println("Beacons are disabled.");
			}
			output.println(
					"Available commands:\n"
					+ "(0): current settings and help\n"
					+ "(1,x): set debug level\n"
					+ "\tx<1: silent mode"
					+ "\n\tx=1: default"
					+ "\n\tx=2: receiver details"
					+ "\n\tx=3: sender details"
					+ "\n\tx=4: link layer details"
					+ "\n\tx=5: beacon layer details"
					+ "\n(2,x): set slot selection"
					+ "\n\tx=0: random"
					+ "\n\tx=1: always take maximum value"
					+ "\n(3,x): set beacon interval"
					+ "\n\tx>0: x is the beacon interval in seconds"
					+ "\n\tx<=0: beacons disabled"
					);
		}
		if (cmd == 1) {
			output.println("Setting debug level to "+ val);
			debugLevel = val;
		}
		if (cmd == 2) {
			if (val == SS_RANDOM) {
				slotSelection = SS_RANDOM;
				output.println("Setting slot selection to random");
			} else if (val == SS_MAX) {
				slotSelection = SS_MAX;
				output.println("Setting slot selection to use the maximum possible time");
			} else {
				output.println("Invalid slot selection setting.");
			}
		}
		if (cmd == 3) {
			if (val > 0) {
				output.println("Setting beacon interval to "+ val + " seconds");
				if (beaconInterval <0 && outgoingQueue.isEmpty()) {
					//if beacons have just been re-enabled, make sure one gets sent
					outgoingQueue.add(Packet.makeBeacon(ourMAC, LinkLayer.getTime(theRF)));
				}
			} else {
				output.println("Beacons are disabled.");
			}
			beaconInterval = val*1000;
		}
		return 0;
	}
	
	protected static int debugLevel() {
		return debugLevel;
	}
	
	protected static int slotSelection() {
		return slotSelection;
	}
	
	protected static int beaconInterval() {
		return beaconInterval;
	}
	
	protected static long getTime(RF rf) {
		return rf.clock() + offset;
	}
	
	protected static void addToOffset(long adjust) {
		offset += adjust;
	}

	protected static void setStatus(int val) {
        if (val > 0 && val < 11) {
            status = val;
        }
    }
}
