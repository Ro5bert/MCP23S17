package com.rrr.mcp23s17;

import com.pi4j.io.gpio.GpioPinDigitalInput;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.event.GpioPinDigitalStateChangeEvent;
import com.pi4j.io.gpio.event.GpioPinListenerDigital;
import com.pi4j.io.spi.SpiChannel;
import com.pi4j.io.spi.SpiDevice;
import com.pi4j.io.spi.SpiFactory;
import com.pi4j.io.spi.SpiMode;

import java.io.IOException;
import java.util.*;

/**
 * <p>
 * An interface for the MCP23S17 SPI IO expander for Raspberry Pi.
 * </p>
 * <p>
 * This class abstracts away the technical details of communicating with the chip via SPI, allowing for simple access
 * via {@link PinView PinView} objects. One {@code PinView} object exists per {@link Pin Pin}, and {@code PinView}
 * objects are initialized lazily. {@code PinView} references are returned by
 * {@link MCP23S17#getPinView(Pin) getPinView}.
 * </p>
 * <p>
 * It is critical to note that, to allow for writing to the MCP23S17's registers in batches, the setter methods on
 * {@code PinView}s (or, in general, the methods that modify state for that pin) do not actually perform a write to the
 * chip; they merely set program state. In order to write to the chip, each invocation of a state-changing
 * {@code PinView} method (or a batch thereof) must be followed by a call to the appropriate {@code writeXXX} method(s)
 * on the {@code MCP23S17} object.
 * </p>
 * <p>
 * To setup the chip to use interrupts, the interrupt {@linkplain GpioPinDigitalInput input pins} must be passed in
 * during construction at the appropriate static factory method; one static factory method is provided for each possible
 * interrupt setup. Once a chip is setup for interrupts, callbacks for both
 * {@linkplain PinView#addListener(InterruptListener) pin-specific interrupts} and
 * {@linkplain MCP23S17#addGlobalListener(InterruptListener) global interrupts} may be registered. Do note, however,
 * that each pin that is supposed to generate interrupts must be setup as such in the appropriate registers. Refer to
 * the <a href=http://ww1.microchip.com/downloads/en/DeviceDoc/20001952C.pdf>MCP23S17 datasheet</a> for more
 * information.
 * </p>
 * <p>
 * The only mutable state in {@code MCP23S17} objects are the bytes representing the register values on the chip, which
 * are accessed non-synchronously in both this class and its inner class, {@code PinView}. As such, any actions
 * modifying these bytes (the only methods that modify them are contained in {@code PinView}) must be synchronized
 * externally, if necessary. Also, calls to the {@code writeXXX} methods must be synchronized if used in threaded
 * applications as otherwise they will all attempt to write to the SPI bus at once.
 * </p>
 *
 * @author Robert Russell
 */
public final class MCP23S17 {

    /**
     * <p>
     * Enum for each physical GPIO pin on the MCP23S17 chip.
     * </p>
     * <p>
     * The pins are renumbered so that port A's pins are in order as {@code PIN0} through {@code PIN7} and port B's pins
     * are in order as {@code PIN8} through {@code PIN15}.
     * </p>
     * @author Robert Russell
     */
    public enum Pin {
        // Port A
        PIN0(0, true),
        PIN1(1, true),
        PIN2(2, true),
        PIN3(3, true),
        PIN4(4, true),
        PIN5(5, true),
        PIN6(6, true),
        PIN7(7, true),

        // Port B
        PIN8(0, false),
        PIN9(1, false),
        PIN10(2, false),
        PIN11(3, false),
        PIN12(4, false),
        PIN13(5, false),
        PIN14(6, false),
        PIN15(7, false);

        private final int pinNumber;
        private final boolean portA;
        /**
         * A bit mask which is used in {@link Pin#getCorrespondingBit(byte) getCorrespondingBit} and
         * {@link Pin#setCorrespondingBit(byte, boolean) setCorrespondingBit}.
         */
        private final byte mask;

        /**
         * @param bitIndex the index of the pin in the bytes which concern it (i.e. the pin number <code>mod 8</code>).
         * @param portA whether or not this pin is in port A.
         */
        Pin(int bitIndex, boolean portA) {
            this.pinNumber = bitIndex + (portA ? 0 : 8);
            this.portA = portA;
            this.mask = (byte) (1 << bitIndex);
        }

        /**
         * <p>
         * Get the pin number.
         * </p>
         * <p>
         * The pins are numbered so that port A's pins are in order as {@code 0} through {@code 7} and port B's pins are
         * in order as {@code 8} through {@code 15}.
         * </p>
         *
         * @return the pin number.
         */
        public int getPinNumber() {
            return pinNumber;
        }

        /**
         * Get whether or not this {@code Pin} is in port A.
         *
         * @return whether or not this {@code Pin} is in port A.
         */
        public boolean isPortA() {
            return portA;
        }

        /**
         * Get whether or not this {@code Pin} is in port B.
         *
         * @return whether or not this {@code Pin} is in port B.
         */
        public boolean isPortB() {
            return !portA;
        }

        /**
         * Given two bytes, one corresponding to port A and one corresponding to port B, return the one corresponding to
         * the port which this {@code Pin} is in.
         *
         * @param byteA the byte corresponding to port A.
         * @param byteB the byte corresponding to port B.
         * @return the byte corresponding to the port which this {@code Pin} is in.
         */
        private byte resolveCorrespondingByte(byte byteA, byte byteB) {
            if (isPortA()) {
                return byteA;
            }
            return byteB;
        }

        /**
         * Given a byte, index it and return the bit corresponding to this pin. (The index is given by this
         * {@code Pin}'s {@linkplain Pin#getPinNumber() pin number} <code>mod 8</code>.)
         *
         * @param b the byte to index.
         * @return the bit corresponding to this pin.
         */
        private boolean getCorrespondingBit(byte b) {
            return (b & mask) > 0;
        }

        /**
         * Given a byte, set the bit corresponding to this pin to the given value and return the resulting byte. (The
         * index at which the bit is set in the byte is given by this {@code Pin}'s
         * {@linkplain Pin#getPinNumber() pin number} <code>mod 8</code>.)
         *
         * @param b the byte to set/clear the bit in.
         * @param value the value to set at the corresponding bit.
         * @return the resulting byte.
         */
        private byte setCorrespondingBit(byte b, boolean value) {
            if (value) {
                return (byte) (b | mask);
            }
            return (byte) (b & ~mask);
        }

        /**
         * Get the {@code Pin} corresponding to the given pin number.
         *
         * @param pinNumber the pin number.
         * @return the {@code Pin} corresponding to the given pin number.
         * @throws IllegalArgumentException if the given pin number is invalid (i.e. less than 0 or greater than 15).
         */
        public static Pin fromPinNumber(int pinNumber) {
            switch (pinNumber) {
                // Port A
                case 0:
                    return PIN0;
                case 1:
                    return PIN1;
                case 2:
                    return PIN2;
                case 3:
                    return PIN3;
                case 4:
                    return PIN4;
                case 5:
                    return PIN5;
                case 6:
                    return PIN6;
                case 7:
                    return PIN7;

                // Port B
                case 8:
                    return PIN8;
                case 9:
                    return PIN9;
                case 10:
                    return PIN10;
                case 11:
                    return PIN11;
                case 12:
                    return PIN12;
                case 13:
                    return PIN13;
                case 14:
                    return PIN14;
                case 15:
                    return PIN15;

                default:
                    throw new IllegalArgumentException("illegal pin number");
            }
        }
    }

    /**
     * An abstraction of each physical GPIO pin on a MCP23S17 IO expander chip.
     *
     * @author Robert Russell
     * @see MCP23S17
     */
    public final class PinView {

        private final Pin pin;

        /**
         * The listeners registered specifically to this pin (i.e. not global listeners). This object is synchronized on
         * so that listeners cannot be added or removed while an interrupt is being processed for vice versa.
         */
        private final Collection<InterruptListener> listeners = new HashSet<>(0);

        private PinView(Pin pin) {
            this.pin = pin;
        }

        /**
         * Get the {@link Pin Pin} which this {@code PinView} represents.
         *
         * @return the {@link Pin Pin} which this {@code PinView} represents.
         */
        public Pin getPin() {
            return pin;
        }

        /**
         * Get the state of the pin. If the pin is output, then the corresponding bit in the corresponding OLATx byte is
         * returned (note: not the actual value in the MCP23S17's OLATx register). If the pin in input, then the actual
         * value at the pin is read and returned.
         *
         * @return the state of the pin.
         * @throws IOException if the pin is input and SPI communication with the MCP23S17 chip failed.
         */
        public boolean get() throws IOException {
            if (isOutput()) {
                return pin.getCorrespondingBit(pin.resolveCorrespondingByte(OLATA, OLATB));
            }
            return pin.getCorrespondingBit(read(pin.resolveCorrespondingByte(ADDR_GPIOA, ADDR_GPIOB)));
        }

        /**
         * Set the pin to the given state. More specifically, this sets the corresponding bit in the corresponding OLATx
         * byte (note: not the actual value in the MCP23S17's OLATx register) to the given state.
         *
         * @param value the value to set on the pin.
         */
        public void set(boolean value) {
            if (pin.isPortA()) {
                OLATA = pin.setCorrespondingBit(OLATA, value);
                // write(ADDR_OLATA, OLATA);
            } else {  // portB
                OLATB = pin.setCorrespondingBit(OLATB, value);
                // write(ADDR_OLATB, OLATB);
            }
        }

        /**
         * Sets the pin high. More specifically, this sets the corresponding bit in the corresponding OLATx byte (note:
         * not the actual value in the MCP23S17's OLATx register).
         */
        public void set() {
            set(true);
        }

        /**
         * Clears the pin (sets it low). More specifically, this clears the corresponding bit in the corresponding OLATx
         * byte (note: not the actual value in the MCP23S17's OLATx register).
         */
        public void clear() {
            set(false);
        }

        /**
         * Get whether or not this pin is input. More specifically, this returns the corresponding bit in the
         * corresponding IODIRx byte (note: not the actual value in the MCP23S17's IODIRx register).
         *
         * @return whether or not this pin is input.
         */
        public boolean isInput() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(IODIRA, IODIRB));
        }

        /**
         * Get whether or not this pin is output. More specifically, this returns the inverse of the corresponding bit
         * in the corresponding IODIRx byte (note: not the actual value in the MCP23S17's IODIRx register).
         *
         * @return whether or not this pin is output.
         */
        public boolean isOutput() {
            return !isInput();
        }

        /**
         * Set the pin to the given direction. More specifically, this sets the corresponding bit in the corresponding
         * IODIRx byte (note: not the actual value in the MCP23S17's IODIRx register) if the pin direction is input, and
         * clears it if the pin direction is output.
         *
         * @param input true for input; false for output.
         */
        public void setDirection(boolean input) {
            if (pin.isPortA()) {
                IODIRA = pin.setCorrespondingBit(IODIRA, input);
                // write(ADDR_IODIRA, IODIRA);
            } else {  // portB
                IODIRB = pin.setCorrespondingBit(IODIRB, input);
                // write(ADDR_IODIRB, IODIRB);
            }
        }

        /**
         * Set the pin to be input. More specifically, this sets the corresponding bit in the corresponding IODIRx byte
         * (note: not the actual value in the MCP23S17's IODIRx register).
         */
        public void setAsInput() {
            setDirection(true);
        }

        /**
         * Set the pin to be output. More specifically, this clears the corresponding bit in the corresponding IODIRx
         * byte (note: not the actual value in the MCP23S17's IODIRx register).
         */
        public void setAsOutput() {
            setDirection(false);
        }

        /**
         * Get whether or not this pin's input is inverted. More specifically, this returns the corresponding bit in the
         * corresponding IPOLx byte (note: not the actual value in the MCP23S17's IPOLx register).
         *
         * @return whether or not this pin's input os inverted.
         */
        public boolean isInputInverted() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(IPOLA, IPOLB));
        }

        /**
         * Set whether or not the pin's input is inverted. More specifically, this sets the corresponding bit in the
         * corresponding IPOLx byte (note: not the actual value in the MCP23S17's IPOLx register) if the pin's input is
         * to be inverted, and clears it if the pin's input is to not be inverted.
         *
         * @param inverted whether or not the pin's input is to be inverted.
         */
        // TODO: this name is misleading and inconsistent; should be "isInputInverted"
        public void setInverted(boolean inverted) {
            if (pin.isPortA()) {
                IPOLA = pin.setCorrespondingBit(IPOLA, inverted);
                // write(ADDR_IPOLA, IPOLA);
            } else {  // portB
                IPOLB = pin.setCorrespondingBit(IPOLB, inverted);
                // write(ADDR_IPOLB, IPOLB);
            }
        }

        /**
         * Set the pin's input to be inverted. More specifically, this sets the corresponding bit in the corresponding
         * IPOLx byte (note: not the actual value in the MCP23S17's IPOLx register).
         */
        public void invertInput() {
            setInverted(true);
        }

        /**
         * Set the pin's input to not be inverted. More specifically, this clears the corresponding bit in the
         * corresponding IPOLx byte (note: not the actual value in the MCP23S17's IPOLx register).
         */
        public void uninvertInput() {
            setInverted(false);
        }

        /**
         * Get whether or not this pin's interrupt is enabled. More specifically, this returns the corresponding bit in
         * the corresponding GPINTENx byte (note: not the actual value in the MCP23S17's GPINTENx register).
         *
         * @return whether or not this pin's interrupt is enabled.
         */
        public boolean isInterruptEnabled() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(GPINTENA, GPINTENB));
        }

        /**
         * Set whether or not the pin's interrupt is enabled. More specifically, this sets the corresponding bit in the
         * corresponding GPINTENx byte (note: not the actual value in the MCP23S17's GPINTENx register) if interrupts
         * are enabled, and clears it if interrupts are disabled.
         *
         * @param interruptEnabled whether or not to enable interrupts.
         */
        public void setInterruptEnabled(boolean interruptEnabled) {
            if (pin.isPortA()) {
                GPINTENA = pin.setCorrespondingBit(GPINTENA, interruptEnabled);
                // write(ADDR_GPINTENA, GPINTENA);
            } else {  // portB
                GPINTENB = pin.setCorrespondingBit(GPINTENB, interruptEnabled);
                // write(ADDR_GPINTENB, GPINTENB);
            }
        }

        /**
         * Enable the pin's interrupts. More specifically, this sets the corresponding bit in the corresponding GPINTENx
         * byte (note: not the actual value in the MCP23S17's GPINTENx register).
         */
        public void enableInterrupt() {
            setInterruptEnabled(true);
        }

        /**
         * Disable the pin's interrupts. More specifically, this clears the corresponding bit in the corresponding
         * GPINTENx byte (note: not the actual value in the MCP23S17's GPINTENx register).
         */
        public void disableInterrupt() {
            setInterruptEnabled(false);
        }

        /**
         * Get the default comparison value for comparison interrupt mode for this pin. More specifically, this returns
         * the corresponding bit in the corresponding DEFVALx byte (note: not the actual value in the MCP23S17's DEFVALx
         * register).
         *
         * @return the default comparison value for comparison interrupt mode for this pin.
         */
        public boolean getDefaultComparisonValue() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(DEFVALA, DEFVALB));
        }

        /**
         * Set the default comparison value for comparison interrupt mode for this pin. More specifically, this sets the
         * corresponding bit in the corresponding DEFVALx byte (note: not the actual value in the MCP23S17's DEFVALx
         * register) to the given value.
         *
         * @param value the default comparison value for comparison interrupt mode for this pin.
         */
        public void setDefaultComparisonValue(boolean value) {
            if (pin.isPortA()) {
                DEFVALA = pin.setCorrespondingBit(DEFVALA, value);
                // write(ADDR_DEFVALA, DEFVALA);
            } else {  // portB
                DEFVALB = pin.setCorrespondingBit(DEFVALB, value);
                // write(ADDR_DEFVALB, DEFVALB);
            }
        }

        /**
         * Get whether or not this pin is in interrupt comparison mode (as opposed to change mode). More specifically,
         * this returns the corresponding bit in the corresponding INTCONx byte (note: not the actual value in the
         * MCP23S17's INTCONx register).
         *
         * @return whether or not this pin is in interrupt comparison mode.
         */
        public boolean isInterruptComparisonMode() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(INTCONA, INTCONB));
        }

        /**
         * Get whether or not this pin is in interrupt change mode (as opposed to comparison mode). More specifically,
         * this returns the inverse of the corresponding bit in the corresponding INTCONx byte (note: not the actual
         * value in the MCP23S17's INTCONx register).
         *
         * @return whether or not this pin is in interrupt change mode.
         */
        public boolean isInterruptChangeMode() {
            return !isInterruptComparisonMode();
        }

        /**
         * Set the interrupt mode for this pin. More specifically, this sets the corresponding bit in the corresponding
         * INTCONx byte (note: not the actual value in the MCP23S17's INTCONx register) if transitioning to comparison
         * mode, and clears it if transitioning to change mode.
         *
         * @param comparison true for comparison mode; false for change mode.
         */
        public void setInterruptMode(boolean comparison) {
            if (pin.isPortA()) {
                INTCONA = pin.setCorrespondingBit(INTCONA, comparison);
                // write(ADDR_INTCONA, INTCONA);
            } else {  // portB
                INTCONB = pin.setCorrespondingBit(INTCONB, comparison);
                // write(ADDR_INTCONB, INTCONB);
            }
        }

        /**
         * Set the interrupt mode to comparison mode. More specifically, this sets the corresponding bit in the
         * corresponding INTCONx byte (note: not the actual value in the MCP23S17's INTCONx register).
         */
        public void toInterruptComparisonMode() {
            setInterruptMode(true);
        }

        /**
         * Set the interrupt mode to change mode. More specifically, this clears the corresponding bit in the
         * corresponding INTCONx byte (note: not the actual value in the MCP23S17's INTCONx register).
         */
        public void toInterruptChangeMode() {
            setInterruptMode(false);
        }

        /**
         * Get whether or not this pin has a pull-up resistor enabled. More specifically, this returns the corresponding
         * bit in the corresponding GPPUx byte (note: not the actual value in the MCP23S17's GPPUx register).
         *
         * @return whether or not this pin has a pull-up resistor enabled.
         */
        public boolean isPulledUp() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(GPPUA, GPPUB));
        }

        /**
         * Set whether or not this pin has a pull-up resistor enabled. More specifically, this sets the corresponding
         * bit in the corresponding GPPUx byte (note: not the actual value in the MCP23S17's GPPUx register) if pull-up
         * resistors are being enabled, and clears it if pull-up resistors are being disabled.
         *
         * @param pulledUp  whether or not to enable pull-up resistors.
         */
        public void setPulledUp(boolean pulledUp) {
            if (pin.isPortA()) {
                GPPUA = pin.setCorrespondingBit(GPPUA, pulledUp);
                // write(ADDR_GPPUA, GPPUA);
            } else {  // portB
                GPPUB = pin.setCorrespondingBit(GPPUB, pulledUp);
                // write(ADDR_GPPUB, GPPUB);
            }
        }

        /**
         * Enables pull-up resistors for this pin. More specifically, this sets the corresponding bit in the
         * corresponding GPPUx byte (note: not the actual value in the MCP23S17's GPPUx register).
         */
        public void enablePullUp() {
            setPulledUp(true);
        }

        /**
         * Disables pull-up resistors for this pin. More specifically, this clears the corresponding bit in the
         * corresponding GPPUx byte (note: not the actual value in the MCP23S17's GPPUx register).
         */
        public void disablePullUp() {
            setPulledUp(false);
        }

        /**
         * <p>
         * Add an {@linkplain InterruptListener interrupt listener} for this pin.
         * </p>
         * <p>
         * This does no error checking with respect to whether or not interrupts are enabled for this pin or whether or
         * not they even could be effectively enabled.
         * </p>
         *
         * @implSpec This is synchronized on the collection of listeners, so it is thread safe.
         *
         * @param listener the listener to add.
         * @throws IllegalArgumentException if the given listener is already registered.
         * @throws NullPointerException if the given listener is {@code null}.
         */
        public void addListener(InterruptListener listener) {
            synchronized (listeners) {
                if (listeners.contains(Objects.requireNonNull(listener, "cannot add null listener"))) {
                    throw new IllegalArgumentException("listener already registered");
                }
                listeners.add(listener);
            }
        }

        /**
         * Remove an {@linkplain InterruptListener interrupt listener} from this pin.
         *
         * @implSpec This is synchronized on the collection of listeners, so it is thread safe.
         *
         * @param listener the listener to remove.
         * @throws IllegalArgumentException if the given listener was not previously registered.
         * @throws NullPointerException if the given listener is {@code null}.
         */
        public void removeListener(InterruptListener listener) {
            synchronized (listeners) {
                if (!listeners.contains(Objects.requireNonNull(listener, "cannot remove null listener"))) {
                    throw new IllegalArgumentException("cannot remove unregistered listener");
                }
                listeners.remove(listener);
            }
        }

        /**
         * Invoke all the interrupt listeners registered to receive events specifically for this pin with the given
         * captured value. This is called from
         * {@link MCP23S17#callInterruptListeners(byte, byte, Pin[]) callInterruptListeners}.
         *
         * @param capturedValue the value on the pin at the time of the interrupt.
         */
        private void relayInterruptToListeners(boolean capturedValue) {
            synchronized (listeners) {
                for (InterruptListener listener : listeners) {
                    listener.onInterrupt(capturedValue, pin);
                }
            }
        }
    }

    /**
     * A {@linkplain FunctionalInterface functional interface} representing an interrupt listener callback.
     * {@code InterruptListener}s are registered via
     * {@link MCP23S17#addGlobalListener(InterruptListener) addGlobalListener} on {@link MCP23S17 MCP23S17} objects and
     * {@link PinView#addListener(InterruptListener) addListener} on {@link PinView PinView} objects, and removed by the
     * similarly-named methods.
     *
     * @author Robert Russell
     */
    @FunctionalInterface
    public interface InterruptListener {

        /**
         * Called whenever an interrupt occurs on a pin for which this listener is registered to receive events.
         *
         * @param capturedValue the value on the pin at the time of the interrupt.
         * @param pin the {@link Pin Pin} on which the interrupt occurred.
         */
        void onInterrupt(boolean capturedValue, Pin pin);
    }

    // TODO: this should be settable by the user
    private static final int SPI_SPEED_HZ = 1000000;  // 1 MHz; Max 10 MHz

    // Register addresses for IOCON.BANK = 0
    private static final byte ADDR_IODIRA = 0x00;
    private static final byte ADDR_IODIRB = 0x01;
    private static final byte ADDR_IPOLA = 0x02;
    private static final byte ADDR_IPOLB = 0x03;
    private static final byte ADDR_GPINTENA = 0x04;
    private static final byte ADDR_GPINTENB = 0x05;
    private static final byte ADDR_DEFVALA = 0x06;
    private static final byte ADDR_DEFVALB = 0x07;
    private static final byte ADDR_INTCONA = 0x08;
    private static final byte ADDR_INTCONB = 0x09;
    private static final byte ADDR_IOCON = 0x0A;
    private static final byte ADDR_GPPUA = 0x0C;
    private static final byte ADDR_GPPUB = 0x0D;
    private static final byte ADDR_INTFA = 0x0E;
    private static final byte ADDR_INTFB = 0x0F;
    private static final byte ADDR_INTCAPA = 0x10;
    private static final byte ADDR_INTCAPB = 0x11;
    private static final byte ADDR_GPIOA = 0x12;
    private static final byte ADDR_GPIOB = 0x13;
    private static final byte ADDR_OLATA = 0x14;
    private static final byte ADDR_OLATB = 0x15;

    private static final byte WRITE_OPCODE = 0x40;
    private static final byte READ_OPCODE = 0x41;

    private static final Pin[] PORT_A_PINS =
            {Pin.PIN0, Pin.PIN1, Pin.PIN2, Pin.PIN3, Pin.PIN4, Pin.PIN5, Pin.PIN6, Pin.PIN7};
    private static final Pin[] PORT_B_PINS =
            {Pin.PIN8, Pin.PIN9, Pin.PIN10, Pin.PIN11, Pin.PIN12, Pin.PIN13, Pin.PIN14, Pin.PIN15};

    private final GpioPinDigitalOutput chipSelect;
    private final SpiDevice spi;
    // TODO: doc -- we sync on this
    private final EnumMap<Pin, PinView> pinViews = new EnumMap<>(Pin.class);
    // TODO: doc -- we sync on this
    private final Collection<InterruptListener> globalListeners = new HashSet<>(0);

    // These are only referenced so they are not GCed.
    private final GpioPinDigitalInput portAInterrupt;
    private final GpioPinDigitalInput portBInterrupt;

    // TODO: make these atomic?
    private byte IODIRA = (byte) 0b11111111;
    private byte IODIRB = (byte) 0b11111111;
    private byte IPOLA = (byte) 0b00000000;
    private byte IPOLB = (byte) 0b00000000;
    private byte GPINTENA = (byte) 0b00000000;
    private byte GPINTENB = (byte) 0b00000000;
    private byte DEFVALA = (byte) 0b00000000;
    private byte DEFVALB = (byte) 0b00000000;
    private byte INTCONA = (byte) 0b00000000;
    private byte INTCONB = (byte) 0b00000000;
    // private byte IOCON = (byte) 0b00000000;  // Unused
    private byte GPPUA = (byte) 0b00000000;
    private byte GPPUB = (byte) 0b00000000;
    private byte OLATA = (byte) 0b00000000;
    private byte OLATB = (byte) 0b00000000;

    private MCP23S17(SpiChannel spiChannel,
                     GpioPinDigitalOutput chipSelect,
                     GpioPinDigitalInput portAInterrupt,
                     GpioPinDigitalInput portBInterrupt)
            throws IOException {
        this.chipSelect = Objects.requireNonNull(chipSelect, "chipSelect must be non-null");
        this.spi = SpiFactory.getInstance(
                Objects.requireNonNull(spiChannel, "spiChannel must be non-null"),
                SPI_SPEED_HZ,
                SpiMode.MODE_0
        );
        this.portAInterrupt = portAInterrupt;
        this.portBInterrupt = portBInterrupt;

        // Take the CS pin high if it is not already since the CS is active low.
        chipSelect.high();
    }

    public PinView getPinView(Pin pin) {
        PinView pinView;
        // This is called from callInterruptListeners when an interrupt occurs, hence the need for sync.
        synchronized (pinViews) {
            pinView = pinViews.get(Objects.requireNonNull(pin, "pin must be non-null"));
            if (pinView == null) {
                pinView = new PinView(pin);
                pinViews.put(pin, pinView);
            }
        }
        return pinView;
    }

    // TODO: doc - does not support remove, return in order 0-15
    public Iterator<PinView> getPinViewIterator() {
        return new Iterator<>() {

            private int current = 0;

            @Override
            public boolean hasNext() {
                return current < 16;
            }

            @Override
            public PinView next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return getPinView(Pin.fromPinNumber(current++));
            }
        };
    }

    // TODO: doc - no error checking for whether ints enabled
    public void addGlobalListener(InterruptListener listener) {
        synchronized (globalListeners) {
            if (globalListeners.contains(listener)) {
                throw new IllegalArgumentException("listener already registered");
            }
            globalListeners.add(Objects.requireNonNull(listener, "cannot add null listener"));
        }
    }

    public void removeGlobalListener(InterruptListener listener) {
        synchronized (globalListeners) {
            if (!globalListeners.contains(listener)) {
                throw new IllegalArgumentException("cannot remove unregistered listener");
            }
            globalListeners.remove(listener);
        }
    }

    private void write(byte registerAddress, byte value) throws IOException {
        try {
            chipSelect.low();
            spi.write(WRITE_OPCODE, registerAddress, value);
        } finally {
            chipSelect.high();
        }
    }

    public void writeIODIRA() throws IOException {
        write(ADDR_IODIRA, IODIRA);
    }

    public void writeIODIRB() throws IOException {
        write(ADDR_IODIRB, IODIRB);
    }

    public void writeIPOLA() throws IOException {
        write(ADDR_IPOLA, IPOLA);
    }

    public void writeIPOLB() throws IOException {
        write(ADDR_IPOLB, IPOLB);
    }

    public void writeGPINTENA() throws IOException {
        write(ADDR_GPINTENA, GPINTENA);
    }

    public void writeGPINTENB() throws IOException {
        write(ADDR_GPINTENB, GPINTENB);
    }

    public void writeDEFVALA() throws IOException {
        write(ADDR_DEFVALA, DEFVALA);
    }

    public void writeDEFVALB() throws IOException {
        write(ADDR_DEFVALB, DEFVALB);
    }

    public void writeINTCONA() throws IOException {
        write(ADDR_INTCONA, INTCONA);
    }

    public void writeINTCONB() throws IOException {
        write(ADDR_INTCONB, INTCONB);
    }

    public void writeGPPUA() throws IOException {
        write(ADDR_GPPUA, GPPUA);
    }

    public void writeGPPUB() throws IOException {
        write(ADDR_GPPUB, GPPUB);
    }

    public void writeOLATA() throws IOException {
        write(ADDR_OLATA, OLATA);
    }

    public void writeOLATB() throws IOException {
        write(ADDR_OLATB, OLATB);
    }

    private byte read(byte registerAddress) throws IOException {
        byte data;
        try {
            chipSelect.low();
            // The 0x00 byte is just arbitrary filler.
            data = spi.write(READ_OPCODE, registerAddress, (byte) 0x00)[2];
        } finally {
            chipSelect.high();
        }
        return data;
    }

    private byte uncheckedRead(byte registerAddress) {
        try {
            return read(registerAddress);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void handlePortAInterrupt() {
        callInterruptListeners(uncheckedRead(ADDR_INTFA), uncheckedRead(ADDR_INTCAPA), PORT_A_PINS);
    }

    private void handlePortBInterrupt() {
        callInterruptListeners(uncheckedRead(ADDR_INTFB), uncheckedRead(ADDR_INTCAPB), PORT_B_PINS);
    }

    private void callInterruptListeners(byte intf, byte intcap, Pin[] pins) {
        for (Pin pin : pins) {
            if (pin.getCorrespondingBit(intf)) {
                boolean capturedValue = pin.getCorrespondingBit(intcap);
                synchronized (globalListeners) {
                    for (InterruptListener listener : globalListeners) {
                        listener.onInterrupt(capturedValue, pin);
                    }
                }
                // This can in rare cases where the IO Extender is already configured create a PinView object before the
                // user indirectly creates it lazily...
                getPinView(pin).relayInterruptToListeners(capturedValue);
                break;
            }
        }
    }

    public static MCP23S17 newWithoutInterrupts(SpiChannel spiChannel,
                                                GpioPinDigitalOutput chipSelect)
            throws IOException {
        return new MCP23S17(
                spiChannel,
                chipSelect,
                null,
                null
        );
    }

    public static MCP23S17 newWithTiedInterrupts(SpiChannel spiChannel,
                                                 GpioPinDigitalOutput chipSelect,
                                                 GpioPinDigitalInput interrupt)
            throws IOException {
        MCP23S17 ioExpander = new MCP23S17(
                spiChannel,
                chipSelect,
                Objects.requireNonNull(interrupt, "interrupt must be non-null"),
                interrupt
        );
        // Set the IOCON.MIRROR bit to OR the INTA and INTB lines together.
        ioExpander.write(ADDR_IOCON, (byte) 0x40);
        attachInterruptOnLow(interrupt, () -> {
            ioExpander.handlePortAInterrupt();
            ioExpander.handlePortBInterrupt();
        });
        return ioExpander;
    }

    public static MCP23S17 newWithInterrupts(SpiChannel spiChannel,
                                             GpioPinDigitalOutput chipSelect,
                                             GpioPinDigitalInput portAInterrupt,
                                             GpioPinDigitalInput portBInterrupt)
            throws IOException {
        MCP23S17 ioExpander = new MCP23S17(
                spiChannel,
                chipSelect,
                Objects.requireNonNull(portAInterrupt, "portAInterrupt must be non-null"),
                Objects.requireNonNull(portBInterrupt, "portBInterrupt must be non-null")
        );
        attachInterruptOnLow(portAInterrupt, ioExpander::handlePortAInterrupt);
        attachInterruptOnLow(portBInterrupt, ioExpander::handlePortBInterrupt);
        return ioExpander;
    }

    public static MCP23S17 newWithPortAInterrupts(SpiChannel spiChannel,
                                                  GpioPinDigitalOutput chipSelect,
                                                  GpioPinDigitalInput portAInterrupt)
            throws IOException {
        MCP23S17 ioExpander = new MCP23S17(
                spiChannel,
                chipSelect,
                Objects.requireNonNull(portAInterrupt, "portAInterrupt must be non-null"),
                null
        );
        attachInterruptOnLow(portAInterrupt, ioExpander::handlePortAInterrupt);
        return ioExpander;
    }

    public static MCP23S17 newWithPortBInterrupts(SpiChannel spiChannel,
                                                  GpioPinDigitalOutput chipSelect,
                                                  GpioPinDigitalInput portBInterrupt)
            throws IOException {
        MCP23S17 ioExpander = new MCP23S17(
                spiChannel,
                chipSelect,
                null,
                Objects.requireNonNull(portBInterrupt, "portBInterrupt must be non-null")
        );
        attachInterruptOnLow(portBInterrupt, ioExpander::handlePortBInterrupt);
        return ioExpander;
    }

    private static void attachInterruptOnLow(GpioPinDigitalInput interrupt, Runnable callback) {
        interrupt.addListener(new GpioPinListenerDigital() {
            @Override
            public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
                if (event.getState().isLow()) {
                    callback.run();
                }
            }
        });
    }
}
