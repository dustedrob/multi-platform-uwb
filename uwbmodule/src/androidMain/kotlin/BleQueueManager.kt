package com.dustedrob.uwb

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Build
import androidx.annotation.RequiresPermission
import java.util.ArrayDeque
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

sealed interface BleCommand {
    class WriteCharacteristic(val characteristic: BluetoothGattCharacteristic, val value: ByteArray, val writeType: Int) : BleCommand
    class WriteDescriptor(val descriptor: BluetoothGattDescriptor, val value: ByteArray) : BleCommand
    // Add ReadCharacteristic, ReadDescriptor as needed
}

class BleQueueManager(private val gatt: BluetoothGatt) {
    private val commandQueue = ArrayDeque<BleCommand>()

    @OptIn(ExperimentalAtomicApi::class)
    private val isBusy = AtomicBoolean(false)

    // Run on a dedicated single-threaded executor or background thread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Synchronized
    fun enqueue(command: BleCommand) {
        commandQueue.add(command)
        processNext()
    }

    // Call this inside your BluetoothGattCallback methods when an operation completes
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @OptIn(ExperimentalAtomicApi::class)
    @Synchronized
    fun operationComplete() {
        isBusy.store(false)
        processNext()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @OptIn(ExperimentalAtomicApi::class)
    @Synchronized
    private fun processNext() {
        if (isBusy.load() || commandQueue.isEmpty()) return

        val nextCommand = commandQueue.poll() ?: return
        isBusy.store(true)

        executeCommand(nextCommand)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun executeCommand(command: BleCommand) {
        when (command) {
            is BleCommand.WriteDescriptor -> {
                // For Android 13+ (API 33+) use the modern signature, fallback for older versions
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