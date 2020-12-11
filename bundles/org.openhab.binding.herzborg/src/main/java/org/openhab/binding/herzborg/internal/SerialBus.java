/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.herzborg.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.TooManyListenersException;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.io.transport.serial.PortInUseException;
import org.openhab.core.io.transport.serial.SerialPort;
import org.openhab.core.io.transport.serial.SerialPortEvent;
import org.openhab.core.io.transport.serial.SerialPortEventListener;
import org.openhab.core.io.transport.serial.SerialPortIdentifier;
import org.openhab.core.io.transport.serial.SerialPortManager;
import org.openhab.core.io.transport.serial.UnsupportedCommOperationException;
import org.openhab.core.thing.ThingStatusDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link SerialBus} implements specific handling for Herzborg serial bus,
 * connected directly via a serial port.
 *
 * @author Pavel Fedin - Initial contribution
 */
public class SerialBus extends Bus implements SerialPortEventListener {
    private final Logger logger = LoggerFactory.getLogger(SerialBus.class);
    private SerialPortManager serialPortManager;
    private @Nullable SerialPort serialPort;

    public SerialBus(SerialPortManager manager) {
        serialPortManager = manager;
    }

    public Result initialize(String port) {
        SerialPortIdentifier portIdentifier = serialPortManager.getIdentifier(port);
        if (portIdentifier == null) {
            return new Result(ThingStatusDetail.CONFIGURATION_ERROR, "No such port: " + port);
        }

        SerialPort commPort;
        try {
            commPort = portIdentifier.open(this.getClass().getName(), 2000);
        } catch (PortInUseException e1) {
            return new Result(ThingStatusDetail.CONFIGURATION_ERROR, "Port " + port + " is in use");
        }

        try {
            // Herzborg serial bus operates with fixed parameters
            commPort.setSerialPortParams(9600, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
            commPort.setFlowControlMode(SerialPort.FLOWCONTROL_NONE);
        } catch (UnsupportedCommOperationException e) {
            return new Result(ThingStatusDetail.CONFIGURATION_ERROR, "Invalid port configuration");
        }

        try {
            commPort.enableReceiveThreshold(8);
            commPort.enableReceiveTimeout(1000);
        } catch (UnsupportedCommOperationException e) {
            // OpenHAB's serial-over-IP doesn't support these, so let's ignore the exception
        }

        InputStream dataIn = null;
        OutputStream dataOut = null;
        String error = null;

        try {
            dataIn = commPort.getInputStream();
            dataOut = commPort.getOutputStream();

            if (dataIn == null) {
                error = "No input stream available on the serial port";
            } else if (dataOut == null) {
                error = "No output stream available on the serial port";
            } else {
                dataOut.flush();
                if (dataIn.markSupported()) {
                    dataIn.reset();
                }

                // RXTX serial port library causes high CPU load
                // Start event listener, which will just sleep and slow down event
                // loop
                // This is copied from SonyProjector binding
                commPort.addEventListener(this);
                commPort.notifyOnDataAvailable(true);
            }
        } catch (IOException | TooManyListenersException e) {
            error = e.getMessage();
        }

        if (error != null) {
            return new Result(ThingStatusDetail.HANDLER_INITIALIZING_ERROR, error);
        }

        this.serialPort = commPort;
        this.dataIn = dataIn;
        this.dataOut = dataOut;

        logger.trace("Successfully initialized");
        return new Result(ThingStatusDetail.NONE);
    }

    @Override
    public void dispose() {
        SerialPort port = serialPort;

        if (port == null) {
            return; // Nothing to do in this case
        }

        port.removeEventListener();
        super.dispose();
        port.close();
        serialPort = null;
    }

    @Override
    public void serialEvent(SerialPortEvent event) {
        // Nothing to do here
    }
}
