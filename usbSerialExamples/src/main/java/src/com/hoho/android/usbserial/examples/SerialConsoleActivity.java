/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package src.com.hoho.android.usbserial.examples;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;

import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.examples.R;
import com.hoho.android.usbserial.util.HexDump;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import src.com.hoho.android.usbserial.examples.bic.ZnpService.response.AbstractResponse;
import src.com.hoho.android.usbserial.examples.bic.ZnpService.response.Response;
import src.com.hoho.android.usbserial.examples.bic.ZnpService.response.ResponseHeader;
import src.com.hoho.android.usbserial.examples.bic.ZnpService.service.ZnpCodes;

import static src.com.hoho.android.usbserial.examples.bic.ZnpService.service.ZnpCodes.bc;
import static src.com.hoho.android.usbserial.examples.bic.ZnpService.service.ZnpCodes.bcResp;
import static src.com.hoho.android.usbserial.examples.bic.ZnpService.service.ZnpCodes.bc_info;
import static src.com.hoho.android.usbserial.examples.bic.ZnpService.service.ZnpCodes.bc_receiveMSG;
import static src.com.hoho.android.usbserial.examples.bic.ZnpService.service.ZnpCodes.bc_reset;
import static src.com.hoho.android.usbserial.examples.bic.ZnpService.service.ZnpCodes.bc_sendToAll;
import static src.com.hoho.android.usbserial.examples.bic.ZnpService.service.ZnpCodes.bc_setChannels;
import static src.com.hoho.android.usbserial.examples.bic.ZnpService.service.ZnpCodes.bc_startCoord;
import static src.com.hoho.android.usbserial.examples.bic.ZnpService.service.ZnpCodes.bc_startRouter;



/**
 * Monitors a single {@link UsbSerialPort} instance, showing all data
 * received.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public class SerialConsoleActivity extends Activity {

    private final String TAG = SerialConsoleActivity.class.getSimpleName();

    /**
     * Driver instance, passed in statically via
     * {@link #show(Context, UsbSerialPort)}.
     *
     * <p/>
     * This is a devious hack; it'd be cleaner to re-create the driver using
     * arguments passed in with the {@link #startActivity(Intent)} intent. We
     * can get away with it because both activities will run in the same
     * process, and this is a simple demo.
     */
    private static UsbSerialPort sPort = null;

    private TextView mTitleTextView;
    private TextView mDumpTextView;

    private ScrollView mScrollView;
    private EditText sendText;
    private Spinner channelSpinner;
    private Button networkButton;
    private Button routerButton;
    private Button coordinatorButton;

    private boolean dontUpdateChannels;


    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private SerialInputOutputManager mSerialIoManager;

    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

        @Override
        public void onRunError(Exception e) {
            Log.d(TAG, "Runner stopped.");
            mDumpTextView.append("ON RUN ERROR.....\n\n");
        }

        @Override
        public void onNewData(final byte[] data) {
            SerialConsoleActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    SerialConsoleActivity.this.updateReceivedData(data);
                }
            });
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.serial_console);
        mTitleTextView = findViewById(R.id.demoTitle);
        mDumpTextView = findViewById(R.id.consoleText);
        mScrollView = findViewById(R.id.demoScroller);
        sendText = findViewById(R.id.sendText);
        channelSpinner = findViewById(R.id.channels);
        networkButton = findViewById(R.id.networkButton);
        routerButton = findViewById(R.id.router);
        coordinatorButton = findViewById(R.id.coordinator);

        setChannel();

        Context context = getApplicationContext();
        Toast channelToast = Toast.makeText(context, channelSpinner.getSelectedItem().toString(), Toast.LENGTH_SHORT);
        channelToast.show();
    }


    @Override
    protected void onPause() {
        super.onPause();
        stopIoManager();
        if (sPort != null) {
            try {
                sPort.close();
            } catch (IOException e) {
                // Ignore.
            }
            sPort = null;
        }
        finish();
    }

    void showStatus(TextView theTextView, String theLabel, boolean theValue){
        String msg = theLabel + ": " + (theValue ? "enabled" : "disabled") + "\n";
        theTextView.append(msg);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "Resumed, port=" + sPort);
        if (sPort == null) {
            mTitleTextView.setText("No serial device.");
        } else {
            final UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

            UsbDeviceConnection connection = usbManager.openDevice(sPort.getDriver().getDevice());
            if (connection == null) {
                mTitleTextView.setText("Opening device failed");
                return;
            }

            try {
                sPort.open(connection);
                sPort.setParameters(38400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            } catch (IOException e) {
                Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
                mTitleTextView.setText("Error opening device: " + e);
                try {
                    sPort.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                sPort = null;
                return;
            }
            mTitleTextView.setText("Serial device: " + sPort.getClass().getSimpleName());
        }
        onDeviceStateChange();
    }

    private void stopIoManager() {
        if (mSerialIoManager != null) {
            Log.i(TAG, "Stopping io manager ..");
            mDumpTextView.append("Stopping io manager ..\n\n");
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }
    }

    private void startIoManager() {
        if (sPort != null) {
            Log.i(TAG, "Starting io manager ..");
            mSerialIoManager = new SerialInputOutputManager(sPort, mListener);
            mExecutor.submit(mSerialIoManager);
        }
    }

    private void onDeviceStateChange() {

        stopIoManager();
        startIoManager();
    }

    private void updateReceivedData(byte[] data) {
        final String message = //"Read " + data.length + " bytes: \n"
                 HexDump.dumpHexString(data) + "\n\n";
        processRx(data);
        mScrollView.smoothScrollTo(0, mDumpTextView.getBottom());
    }

    /**
     * Starts the activity, using the supplied driver instance.
     *
     * @param context
     * @param port
     */
    public static void show(Context context, UsbSerialPort port) {
        sPort = port;
        final Intent intent = new Intent(context, SerialConsoleActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_HISTORY);
        context.startActivity(intent);
    }


    public void sendButton(View v){
        try {
            String msg = sendText.getText().toString();
            Toast.makeText(this, msg.getBytes().toString(), Toast.LENGTH_SHORT).show();
            senden(msg);
            sendText.setText("");
        } catch (Exception e) {
            e.getCause();
        }

    }

    public void senden(String msg) {
        try {
            byte[] message = generateMessage(bc, bc_sendToAll, msg.getBytes());

            try {
                mSerialIoManager.getmDriver().write(message, 500);
            } catch (IOException e) {
                e.printStackTrace();
                mDumpTextView.append(e.getMessage());
            }
        } catch (Exception e) {
            Toast.makeText(this, e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void startRouter(View view) {
        byte[] routerBytes = generateMessage(bc, bc_startRouter, new byte[0]);
        try {
            mSerialIoManager.getmDriver().write(routerBytes, 500);
        } catch (IOException e) {
            e.printStackTrace();
            mDumpTextView.append(e.getMessage());
        }
    }

    public void startCoordinator(View view){
        byte[] coordBytes = generateMessage(bc, bc_startCoord, new byte[0]);
        try {
            mSerialIoManager.getmDriver().write(coordBytes, 500);
        } catch (IOException e) {
            e.printStackTrace();
            mDumpTextView.append(e.getMessage());
        }

    }

    public void networkInfo(View view){
        byte[] infoBytes = generateMessage(bc, bc_info, new byte[0]);
        try {
            mSerialIoManager.getmDriver().write(infoBytes, 500);
        } catch (IOException e) {
            e.printStackTrace();
            mDumpTextView.append(e.getMessage());
        }

    }

    public void resetDevice(View view) {
        byte[] resetBytes = generateMessage(bc, bc_reset, new byte[0]);
        

        try {
            mSerialIoManager.getmDriver().write(resetBytes, 500);
            
        } catch (IOException e) {
            e.printStackTrace();
            mDumpTextView.append(e.getMessage());
        }

        

    }


    public void setChannel() {
        channelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (dontUpdateChannels)
                    dontUpdateChannels = false;
                else {
                    byte[] channelBytes = generateMessage(bc, bc_setChannels, ZnpCodes.Channels.values()[position].value());
                    try {
                        mSerialIoManager.getmDriver().write(channelBytes, 500);
                    } catch (IOException e) {
                        e.printStackTrace();
                        mDumpTextView.append(e.getMessage());
                    }

                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
    }


    private byte[] generateMessage(int cmd0, int cmd1, byte[] data) {

        int dataLength;
        if (data == null)
            dataLength = 0;
        else
            dataLength = data.length;

        byte[] tmpBytes = new byte[dataLength + 5];
        int pointer = 0;
        tmpBytes[pointer++] = (byte) 254; // SOP
        tmpBytes[pointer++] = (byte) dataLength;
        tmpBytes[pointer++] = (byte) cmd0;
        tmpBytes[pointer++] = (byte) cmd1;

        if (data != null) {
            for (byte dataByte : data) {
                tmpBytes[pointer++] = dataByte;
            }
        }
        tmpBytes[pointer] = calcFCS(tmpBytes);
        return tmpBytes;
    }

    private byte calcFCS(byte[] bytes) {
        byte xorResult;
        xorResult = 0;
        for (int i = 1; i < bytes.length - 1; i++) {
            xorResult = (byte) (xorResult ^ bytes[i]);
        }
        return xorResult;
    }



    public void processRxCallBack(byte[] message, ResponseHeader responseHeader) {

        if (responseHeader.getCmd0() == (byte) bcResp) {
            if (responseHeader.getCmd1() == bc_receiveMSG || responseHeader.getCmd1() == bc_sendToAll) {

                appendChatResponse(new AbstractResponse(responseHeader, message) {
                    @Override
                    protected byte[] createPrivateMessage(byte[] bytes, byte[] bytes1) {
                        return new byte[0];
                    }
                });
            }
            else if (responseHeader.getCmd1() == bc_info) {
                updateActionBar(message, responseHeader);
            }
        }
        else {
            appendSystemResponse(new AbstractResponse(responseHeader, message) {
                @Override
                protected byte[] createPrivateMessage(byte[] bytes, byte[] bytes1) {
                    return new byte[0];
                }
            });
        }
    }

    private void processRx(byte[] buffer) {
        //mDumpTextView.append("RAW " + toHex(buffer) + "\n\n");
        int pointer = this.fetchSOP(buffer);
        if (pointer != 0) {
            ResponseHeader header;
            byte[] message;
            if ((buffer[pointer + 1] & 255) == 170) {
                header = new ResponseHeader(buffer[pointer++], buffer[pointer++], buffer[pointer++], buffer[pointer++], buffer[pointer++], buffer[pointer++], buffer[pointer++], buffer[pointer++]);
                if (header.getLength() > 5) {
                    message = Arrays.copyOfRange(buffer, pointer, pointer + header.getLength() - 6);
                } else {
                    message = new byte[0];
                }
            } else {
                header = new ResponseHeader(buffer[pointer++], buffer[pointer++], buffer[pointer++], buffer[pointer], (byte)0, (byte)0, (byte)0, (byte)0);
                if (header.getLength() > 1) {
                    message = Arrays.copyOfRange(buffer, pointer, pointer + header.getLength());
                } else {
                    message = new byte[0];
                }
            }

            processRxCallBack(message, header);
        }
    }

    private int fetchSOP(byte[] buffer) {
        if (buffer == null) {
            return 0;
        } else {
            int pointer = 0;

            while(pointer < buffer.length - 1) {
                if (buffer[pointer++] != 255) {
                    return pointer;
                }
            }

            return 0;
        }
    }


    public void appendChatResponse(Response response) {
        // Get a handler that can be used to post to the main thread
        Handler mainHandler = new Handler(Looper.getMainLooper());

        Runnable myRunnable = new Runnable() {
            @Override
            public void run() {
                String message = "<" + response.getHeader().getIp16() + "> " + response.getMessageAsString() + "\n\n";
                mDumpTextView.append(message);
                mScrollView.smoothScrollTo(0, mDumpTextView.getBottom());
            }
        };
        mainHandler.post(myRunnable);
    }


    public void appendSystemResponse(Response response) {
        // Get a handler that can be used to post to the main thread
        Handler mainHandler = new Handler(Looper.getMainLooper());

        Runnable myRunnable = new Runnable() {
            @Override
            public void run() {
                String message = "<SYSTEM> " + response.getMessageAsHex() + "\n\n";
                mDumpTextView.append(message);
            }
        };
        mainHandler.post(myRunnable);
    }

    public void setChannel(ZnpCodes.Channels channel){
        dontUpdateChannels = true;
        channelSpinner.setSelection(channel.ordinal());
    }


    public void updateActionBar(byte[] message, ResponseHeader responseHeader) {

        networkButton.setText(responseHeader.getIp16());

        setChannel(ZnpCodes.Channels.resolve(responseHeader.getChannel()));

        if (message[0] == 1)
            networkButton.setBackgroundColor(getResources().getColor(R.color.network));
        else
            networkButton.setBackgroundColor(getResources().getColor(R.color.noNetwork));

        ZnpCodes.DevStates devState = ZnpCodes.DevStates.values()[message[1]];
        routerButton.setTextColor(getResources().getColor(R.color.blackText));
        coordinatorButton.setTextColor(getResources().getColor(R.color.blackText));
        if (devState == ZnpCodes.DevStates.DEV_ZB_COORD)
            coordinatorButton.setTextColor(getResources().getColor(R.color.activRole));
        else if (devState == ZnpCodes.DevStates.DEV_ROUTER)
            routerButton.setTextColor(getResources().getColor(R.color.activRole));

    }
}
