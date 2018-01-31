/*
 * Copyright 2018 Roberto Leinardi.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.leinardi.android.things.driver.tsl256x;

import android.os.SystemClock;
import android.support.annotation.IntDef;
import android.util.Log;

import com.google.android.things.pio.I2cDevice;
import com.google.android.things.pio.PeripheralManagerService;

import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static com.leinardi.android.things.driver.tsl256x.Tsl256x.Gain.GAIN_16X;
import static com.leinardi.android.things.driver.tsl256x.Tsl256x.Gain.GAIN_1X;
import static com.leinardi.android.things.driver.tsl256x.Tsl256x.IntegrationTime.INTEGRATION_TIME_101MS;
import static com.leinardi.android.things.driver.tsl256x.Tsl256x.IntegrationTime.INTEGRATION_TIME_13_7MS;
import static com.leinardi.android.things.driver.tsl256x.Tsl256x.IntegrationTime.INTEGRATION_TIME_402MS;
import static com.leinardi.android.things.driver.tsl256x.Tsl256x.Mode.MODE_ACTIVE;
import static com.leinardi.android.things.driver.tsl256x.Tsl256x.Mode.MODE_STANDBY;
import static com.leinardi.android.things.driver.tsl256x.Tsl256x.PackageType.CS;
import static com.leinardi.android.things.driver.tsl256x.Tsl256x.PackageType.T_FN_CL;
import static java.lang.Math.pow;

/**
 * Driver for the TSL256x light-to-digital converter.
 */
@SuppressWarnings("WeakerAccess")
public class Tsl256x implements Closeable {
    public static final int I2C_ADDRESS = 0x39;
    public static final float MAX_RANGE_LUX = 40_000;
    public static final float MAX_POWER_CONSUMPTION_UA = 600;
    private static final String TAG = Tsl256x.class.getSimpleName();

    private static final int COMMAND_BIT = 0b1000_0000;        // Must be 1
    private static final int CLEAR_BIT = 0b0100_0000;          // Clears any pending interrupt (write 1 to clear)
    private static final int WORD_BIT = 0b0010_0000;           // 1 = read/write word (rather than byte)
    private static final int BLOCK_BIT = 0b0001_0000;          // 1 = using block read/write

    private static final int REGISTER_CONTROL = 0x00;          // Control/power register
    private static final int REGISTER_TIMING = 0x01;           // Set integration time register
    private static final int REGISTER_THRESHHOLDL_LOW = 0x02;  // Interrupt low threshold low-byte
    private static final int REGISTER_THRESHHOLDL_HIGH = 0x03; // Interrupt low threshold high-byte
    private static final int REGISTER_THRESHHOLDH_LOW = 0x04;  // Interrupt high threshold low-byte
    private static final int REGISTER_THRESHHOLDH_HIGH = 0x05; // Interrupt high threshold high-byte
    private static final int REGISTER_INTERRUPT = 0x06;        // Interrupt settings
    private static final int REGISTER_CRC = 0x08;              // Factory use only
    private static final int REGISTER_ID = 0x0A;               // TSL2561 identification setting
    private static final int REGISTER_CHAN0_LOW = 0x0C;        // Light data channel 0, low byte
    private static final int REGISTER_CHAN0_HIGH = 0x0D;       // Light data channel 0, high byte
    private static final int REGISTER_CHAN1_LOW = 0x0E;        // Light data channel 1, low byte
    private static final int REGISTER_CHAN1_HIGH = 0x0F;       // Light data channel 1, high byte

    private static final int ID_PART_NUMBER = 0b1111_0000;
    private static final int ID_REVISION_NUMBER = 0b0000_1111;
    private static final int TSL2560_ID = 0b0000_0000;
    private static final int TSL2561_ID = 0b0001_0000;
    private static final int TSL2562_ID = 0b0010_0000;
    private static final int TSL2563_ID = 0b0011_0000;
    private static final int TSL2560T_FN_CL_ID = 0b0100_0000;
    private static final int TSL2561T_FN_CL_ID = 0b0101_0000;

    // Auto-gain thresholds
    private static final int AGC_THI_13MS = 4850;    // Max value at Ti 13ms = 5047
    private static final int AGC_TLO_13MS = 100;     // Min value at Ti 13ms = 100
    private static final int AGC_THI_101MS = 36000;  // Max value at Ti 101ms = 37177
    private static final int AGC_TLO_101MS = 200;    // Min value at Ti 101ms = 200
    private static final int AGC_THI_402MS = 63000;  // Max value at Ti 402ms = 65535
    private static final int AGC_TLO_402MS = 500;    // Min value at Ti 402ms = 500

    private byte mChipId;
    private boolean mAutoGain;
    @IntegrationTime
    private int mIntegrationTime;
    @Gain
    private int mGain;
    @PackageType
    private int mPackageType;
    private I2cDevice mDevice;

    /**
     * Create a new TSL2561 driver connected to the given I2C bus.
     *
     * @param i2cName    I2C bus name the display is connected to
     * @param i2cAddress I2C address of the display
     * @throws IOException
     */
    public Tsl256x(String i2cName, int i2cAddress) throws IOException {
        PeripheralManagerService pioService = new PeripheralManagerService();
        I2cDevice device = pioService.openI2cDevice(i2cName, i2cAddress);
        try {
            connect(device);
        } catch (IOException | RuntimeException e) {
            try {
                close();
            } catch (IOException | RuntimeException ignored) {
            }
            throw e;
        }
    }

    private void connect(I2cDevice device) throws IOException {
        if (mDevice != null) {
            throw new IllegalStateException("device already connected");
        }
        mDevice = device;

        @PackageType int packageType = CS;

        mChipId = readRegByte(REGISTER_ID);
        int partNumber = (mChipId & ID_PART_NUMBER) & 0xFF;
        switch (partNumber) {
            case TSL2560_ID:
                Log.d(TAG, "Found TSL2560");
                break;
            case TSL2561_ID:
                Log.d(TAG, "Found TSL2561");
                break;
            case TSL2562_ID:
                Log.d(TAG, "Found TSL2562");
                break;
            case TSL2563_ID:
                Log.d(TAG, "Found TSL2563");
                break;
            case TSL2560T_FN_CL_ID:
                Log.d(TAG, "Found TSL2560T/FN/CL");
                packageType = T_FN_CL;
                break;
            case TSL2561T_FN_CL_ID:
                Log.d(TAG, "Found TSL2561T/FN/CL");
                packageType = T_FN_CL;
                break;
            default:
                throw new IllegalStateException("Could not find a TSL256x, check wiring!");
        }

        setPackageType(packageType);
        setIntegrationTime(INTEGRATION_TIME_402MS);
        setGain(GAIN_16X);

        /* Note: by default, the device is in power down mode on bootup */
        setMode(MODE_STANDBY);
    }

    /**
     * Close the driver and the underlying device.
     */
    @Override
    public void close() throws IOException {
        if (mDevice != null) {
            try {
                mDevice.close();
            } finally {
                mDevice = null;
            }
        }
    }

    public byte getChipId() {
        return mChipId;
    }

    @PackageType
    public int getPackageType() {
        return mPackageType;
    }

    public void setPackageType(@PackageType int packageType) {
        mPackageType = packageType;
    }

    public boolean isAutoGainEnabled() {
        return mAutoGain;
    }

    public void setAutoGain(boolean autoGain) {
        mAutoGain = autoGain;
    }

    public void setIntegrationTime(@IntegrationTime int integrationTime) throws IOException {
        setMode(MODE_ACTIVE);
        writeRegByte(COMMAND_BIT | REGISTER_TIMING, (byte) ((integrationTime | mGain) & 0xFF));
        setMode(MODE_STANDBY);
        mIntegrationTime = integrationTime;
    }

    public void setGain(@Gain int gain) throws IOException {
        setMode(MODE_ACTIVE);
        writeRegByte(COMMAND_BIT | REGISTER_TIMING, (byte) ((mIntegrationTime | gain) & 0xFF));
        setMode(MODE_STANDBY);
        mGain = gain;
    }

    public int[] readLuminosity() throws IOException {
        setMode(MODE_ACTIVE);
        // Wait x ms for ADC to complete
        switch (mIntegrationTime) {
            case INTEGRATION_TIME_13_7MS:
                SystemClock.sleep(50);
                break;
            case INTEGRATION_TIME_101MS:
                SystemClock.sleep(150);
                break;
            case INTEGRATION_TIME_402MS:
                SystemClock.sleep(450);
                break;
        }
        int[] luminosities;
        if (isAutoGainEnabled()) {
            boolean check = false;
            boolean validRangeFound = false;

            do {
                int ch0;
                int tresholdHigh = 0;
                int tresholdLow = 0;
                @IntegrationTime
                int integrationTime = mIntegrationTime;

                // Get the hi/low threshold for the current integration time
                switch (integrationTime) {
                    case INTEGRATION_TIME_13_7MS:
                        tresholdHigh = AGC_THI_13MS;
                        tresholdLow = AGC_TLO_13MS;
                        break;
                    case INTEGRATION_TIME_101MS:
                        tresholdHigh = AGC_THI_101MS;
                        tresholdLow = AGC_TLO_101MS;
                        break;
                    case INTEGRATION_TIME_402MS:
                        tresholdHigh = AGC_THI_402MS;
                        tresholdLow = AGC_TLO_402MS;
                        break;
                }
                luminosities = readLuminosityData();
                ch0 = luminosities[0];

                // Run an auto-gain check if we haven't already done so...
                if (!check) {
                    if ((ch0 < tresholdLow) && (mGain == GAIN_1X)) {
                        // Increase the gain and try again
                        setGain(GAIN_16X);
                        // Drop the previous conversion results
                        luminosities = readLuminosityData();
                        // Set a flag to indicate we've adjusted the gain
                        check = true;
                    } else if ((ch0 > tresholdHigh) && (mGain == GAIN_16X)) {
                        // Drop gain to 1x and try again
                        setGain(GAIN_1X);
                        // Drop the previous conversion results
                        luminosities = readLuminosityData();
                        // Set a flag to indicate we've adjusted the gain
                        check = true;
                    } else {
                        // Nothing to look at here, keep moving ....
                        // Reading is either valid, or we're already at the chips limits
                        validRangeFound = true;
                    }
                } else {
                    // If we've already adjusted the gain once, just return the new results.
                    // This avoids endless loops where a value is at one extreme pre-gain,
                    // and the the other extreme post-gain
                    validRangeFound = true;
                }
            } while (!validRangeFound);
        } else {
            luminosities = readLuminosityData();
        }
        setMode(MODE_STANDBY);
        return luminosities;
    }

    private int[] readLuminosityData() throws IOException {
        int[] luminosities = new int[3];
        luminosities[0] = readRegWord(COMMAND_BIT | WORD_BIT | REGISTER_CHAN0_LOW) & 0xFFFF;
        luminosities[1] = readRegWord(COMMAND_BIT | WORD_BIT | REGISTER_CHAN1_LOW) & 0xFFFF;
        luminosities[2] = luminosities[0] - luminosities[1];
        return luminosities;
    }

    public float readLux() throws IOException {
        int[] luminosities = readLuminosity();
        // Convert from unsigned integer to floating point
        float ch0 = luminosities[0];
        float ch1 = luminosities[1];

        // We will need the ratio for subsequent calculations
        float ratio = ch1 / ch0;

        float time = 0;
        switch (mIntegrationTime) {
            case INTEGRATION_TIME_13_7MS:
                time = 13.7f;
                break;
            case INTEGRATION_TIME_101MS:
                time = 101f;
                break;
            case INTEGRATION_TIME_402MS:
                time = 402f;
                break;
        }

        // Normalize for integration time
        ch0 *= (402.0 / time);
        ch1 *= (402.0 / time);

        // Normalize for gain
        if (mGain == GAIN_1X) {
            ch0 *= 16;
            ch1 *= 16;
        }

        // Determine lux per datasheet equations
        float lux = 0;
        if (mPackageType == CS) {
            if (ratio < 0.52) {
                lux = 0.0315f * ch0 - 0.0593f * ch0 * (float) pow(ratio, 1.4);
            } else if (ratio < 0.65) {
                lux = 0.0229f * ch0 - 0.0291f * ch1;
            } else if (ratio < 0.80) {
                lux = 0.0157f * ch0 - 0.0180f * ch1;
            } else if (ratio < 1.30) {
                lux = 0.00338f * ch0 - 0.00260f * ch1;
            }
        } else {
            if (ratio < 0.5) {
                lux = 0.0304f * ch0 - 0.062f * ch0 * (float) pow(ratio, 1.4);
            } else if (ratio < 0.61) {
                lux = 0.0224f * ch0 - 0.031f * ch1;
            } else if (ratio < 0.80) {
                lux = 0.0128f * ch0 - 0.0153f * ch1;
            } else if (ratio < 1.30) {
                lux = 0.00146f * ch0 - 0.00112f * ch1;
            }
        }
        return lux;
    }

    /**
     * Set current power mode.
     *
     * @throws IOException
     * @throws IllegalStateException
     */
    public void setMode(@Mode int mode) throws IOException, IllegalStateException {
        writeRegByte(COMMAND_BIT | REGISTER_CONTROL, (byte) mode);
    }

    /**
     * Get current power mode.
     *
     * @throws IOException
     * @throws IllegalStateException
     */
    @SuppressWarnings("ResourceType")
    public int getMode() throws IOException, IllegalStateException {
        return readRegByte(REGISTER_CONTROL) & MODE_ACTIVE;
    }

    private byte readRegByte(int reg) throws IOException {
        if (mDevice == null) {
            throw new IllegalStateException("I2C Device not open");
        }
        return mDevice.readRegByte(reg);
    }

    private void writeRegByte(int reg, byte data) throws IOException {
        if (mDevice == null) {
            throw new IllegalStateException("I2C Device not open");
        }
        mDevice.writeRegByte(reg, data);
    }

    private short readRegWord(int reg) throws IOException {
        if (mDevice == null) {
            throw new IllegalStateException("I2C Device not open");
        }
        return mDevice.readRegWord(reg);
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({INTEGRATION_TIME_13_7MS,
            INTEGRATION_TIME_101MS,
            INTEGRATION_TIME_402MS
    })
    public @interface IntegrationTime {
        int INTEGRATION_TIME_13_7MS = 0b0000_0000;  // 13.7ms
        int INTEGRATION_TIME_101MS = 0b0000_0001; // 101ms
        int INTEGRATION_TIME_402MS = 0b0000_0010; // 402ms
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({GAIN_1X, GAIN_16X
    })
    public @interface Gain {
        int GAIN_1X = 0b0000_0000;     // No gain
        int GAIN_16X = 0b0001_0000;    // 16x gain
    }

    /**
     * Power mode.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({MODE_STANDBY, MODE_ACTIVE})
    public @interface Mode {
        int MODE_STANDBY = 0b0000_0000; // i2c on, output off, low power
        int MODE_ACTIVE = 0b0000_0011;  // i2c on, output on
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({CS, T_FN_CL})
    public @interface PackageType {
        int CS = 0;
        int T_FN_CL = 3;
    }
}
