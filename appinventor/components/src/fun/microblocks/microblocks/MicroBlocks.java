// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2024 John Maloney, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

// Questions for Evan:
// Can we instantiate BluetoothLE internally?

package fun.microblocks.microblocks;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_SCAN;
import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.*;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.EventDispatcher;
import com.google.appinventor.components.runtime.util.ErrorMessages;
import edu.mit.appinventor.ble.BluetoothLE;
import edu.mit.appinventor.ble.BLEPacketReader;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;


@DesignerComponent(version = 1,
  description = "MicroBlocks BLE Extension",
  category = ComponentCategory.EXTENSION,
  nonVisible = true,
  iconName = "aiwebres/microblocks.png")
@SimpleObject(external = true)
public class MicroBlocks
  extends AndroidNonvisibleComponent
  implements BluetoothLE.BluetoothConnectionListener {

  public MicroBlocks(ComponentContainer container) {
    super(container.$form());
  }

  private static final String MB_SERVICE_UUID = "BB37A001-B922-4018-8E74-E14824B3A638";
  private static final String MB_UUID_RX = "BB37A002-B922-4018-8E74-E14824B3A638";
  private static final String MB_UUID_TX = "BB37A003-B922-4018-8E74-E14824B3A638";

  private BluetoothLE bleDevice;
  private List<Integer> recvbuf = new ArrayList<Integer>();

  // Debugging support

  private String debugLog = "";
  private void debug(String s) { debugLog = debugLog + s + "\n"; }

  /**
   * Returns the debug log string.
   */
//   @SimpleFunction
//   public String DebugLog() {
//      return debugLog;
//   }

  /**
   * Connects to the MicroBlocks device with the given <code>name</code>.
   * @param bleExtension The BluetoothLE extension instance.
   * @param name The name advertised by the MicroBlocks device.
   */
  @SimpleFunction
  @UsesPermissions({BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION})
  public void Connect(final BluetoothLE bleExtension, final String name) {
    if (bleExtension == null) return;
    if (bleDevice != null) bleDevice.removeConnectionListener(this);
    bleDevice = bleExtension;
    bleDevice.addConnectionListener(this);
    bleDevice.ConnectToDeviceWithServiceAndName(MB_SERVICE_UUID, name);
  }

  /**
   * Returns true if a device is connected.
   */
  @SimpleFunction
  public boolean IsDeviceConnected() {
     if (bleDevice == null) return false;
     return bleDevice.IsDeviceConnected();
  }

  /**
   * Disconnects from the currently connected BluetoothLE device, if any.
   */
  @SimpleFunction
  public void Disconnect() {
    if (IsDeviceConnected()) {
      bleDevice.Disconnect();
    }
  }

  /**
   * This event is run when a device is connected or disconnected.
   * @param isConnected True if the device is now connected.
   */
  @SimpleEvent
  public void ConnectionChanged(boolean isConnected) {
    if (isConnected) {
      bleDevice.RequestMTU(256);
    }
    EventDispatcher.dispatchEvent(this, "ConnectionChanged", isConnected);
  }

  /**
   * Send the given message to MicroBlocks.
   * @param message Message to send.
   */
  @SimpleFunction
  public void SendMessage(String message) {
    byte[] body = message.getBytes(StandardCharsets.UTF_8);
    int len = body.length + 1; // include terminator byte in length

    List<Integer> msgBytes = new ArrayList<Integer>();
    msgBytes.add(0xFB);
    msgBytes.add(0x1B);
    msgBytes.add(0);
    msgBytes.add(len & 255);
    msgBytes.add((len >> 8) & 255);
    for (byte b : body) { msgBytes.add(Byte.toUnsignedInt(b)); }
    msgBytes.add(0xFE); // terminator byte

    if (bleDevice != null) {
      bleDevice.ExWriteByteValues(MB_SERVICE_UUID, MB_UUID_RX, false, msgBytes);
    }
  }

  /**
   * This event is run when a MicroBlocks message is received.
   * @param String The received message.
   */
  @SimpleEvent
  public void MicroBlocksMessageReceived(final String message) {
    EventDispatcher.dispatchEvent(this, "MicroBlocksMessageReceived",  message);
  }

  public void onConnected(BluetoothLE bleConnection) {
    bleDevice.ExRegisterForByteValues(MB_SERVICE_UUID, MB_UUID_TX, false, receiveBytes);
    recvbuf = new ArrayList<Integer>(); // clear receive buffer
    ConnectionChanged(true);
  }

  public void onDisconnected(BluetoothLE bleConnection) {
    ConnectionChanged(false);
  }

  private final BluetoothLE.BLEResponseHandler<Integer> receiveBytes =
    new BluetoothLE.BLEResponseHandler<Integer>() {
      @Override
      public void onReceive(String serviceUuid, String characteristicUuid, List<Integer> values) {
        processReceivedData(values);
      }
    };

  private final void processReceivedData(List<Integer> newData) {
    final int LONG_MSG_START = 251;
    final int BROADCAST_MSG = 27;

    recvbuf.addAll(newData);

    int start = 0;
    while (true) {
      // skip to the start of the next long message
      while (start < recvbuf.size()) {
        if (recvbuf.get(start) == LONG_MSG_START) break;
        start++;
      }
      if ((start + 5) > recvbuf.size()) break; // not found or incomplete long message header

      // extract length from header (two unsigned bytes, LSB)
      int msgLength = (recvbuf.get(start + 4) << 8) + recvbuf.get(start + 3);
      if ((start + 5 + msgLength) > recvbuf.size()) break; // incomplete message

      // process the message
      if (recvbuf.get(start + 1) == BROADCAST_MSG) { // got a broadcast: extract and report it
        byte[] msgBytes = new byte[msgLength];
        for (int i = 0; i < msgLength; i++) {
          int n = recvbuf.get(start + 5 + i).intValue();
          msgBytes[i] = (byte) ((n > 127) ? n - 256 : n);
        }
        String message = new String(msgBytes, StandardCharsets.UTF_8);
        MicroBlocksMessageReceived(message);
      }
      start += 5 + msgLength;
    }

    // remove processed bytes from recvBuf
    if (start >= recvbuf.size()) {
      recvbuf.clear();
    } else {
      recvbuf = new ArrayList<>(recvbuf.subList(start, recvbuf.size()));
    }
  }

}
