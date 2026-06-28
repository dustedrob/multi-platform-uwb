package com.dustedrob.uwb

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Build
import androidx.annotation.RequiresPermission
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** A single GATT operation to run through the serialized [BleQueueManager]. */
sealed interface BleCommand {
    class WriteCharacteristic(
        val characteristic: BluetoothGattCharacteristic,
        val value: ByteArray,
        val writeType: Int,
    ) : BleCommand

    class WriteDescriptor(
        val descriptor: BluetoothGattDescriptor,
        val value: ByteArray,
    ) : BleCommand
}

/**
 * Serializes GATT writes for a single [BluetoothGatt] connection. Android's GATT stack only allows
 * one outstanding operation at a time; issuing a second before the first completes silently drops it.
 * Callers must invoke [operationComplete] from the matching GATT callback (e.g. `onCharacteristicWrite`,
 * `onDescriptorWrite`) so the next queued command runs.
 */
class BleQueueManager(private val gatt: BluetoothGatt) {
    private val commandQueue = ArrayDeque<BleCommand>()
    private val isBusy = AtomicBoolean(false)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Synchronized
    fun enqueue(command: BleCommand) {
        commandQueue.add(command)
        processNext()
    }

    /** Call from the GATT callback when the in-flight operation finishes, to release the next one. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Synchronized
    fun operationComplete() {
        isBusy.set(false)
        processNext()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Synchronized
    private fun processNext() {
        if (isBusy.get() || commandQueue.isEmpty()) return
        val next = commandQueue.poll() ?: return
        isBusy.set(true)
        executeCommand(next)
    }

    @Suppress("DEPRECATION")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun executeCommand(command: BleCommand) {
        when (command) {
            is BleCommand.WriteDescriptor -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(command.descriptor, command.value)
                } else {
                    command.descriptor.value = command.value
                    gatt.writeDescriptor(command.descriptor)
                }
            }

            is BleCommand.WriteCharacteristic -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(command.characteristic, command.value, command.writeType)
                } else {
                    command.characteristic.value = command.value
                    command.characteristic.writeType = command.writeType
                    gatt.writeCharacteristic(command.characteristic)
                }
            }
        }
    }
}
