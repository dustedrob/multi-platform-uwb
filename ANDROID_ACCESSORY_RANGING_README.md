# Android UWB ranging support — package & 
# addition to the Qorvo DW3_QM33_SDK_1.1.1 SDK QANI builds, for all devices
#  this also includes the fix for the 3000 EVB on the nRF52840DK 

the flashable binaries for those devices are available separately outside this repo


### Static-STS key handling (working, not hardcoded)
The app generates a NEW random 8-byte `sessionKeyInfo` each run, passes it into
its own ranging params AND sends the same bytes to the firmware. Both ends thus
share identical bytes.  derives the on-air vUpper64 as:

- `Vendor_ID`     = `sessionKeyInfo[0..1]` **REVERSED**  → `{token[1], token[0]}`
- `Static_STS_IV` = `sessionKeyInfo[2..7]` in order
- `vupper64`      = `IV[0..5] ++ VendorId`

The VendorId-byte reversal is a fixed reordering (matches how androidx assembles
its vUpper64) and is independent of the byte values, so ANY random 8-byte key works.


the Android protocol defined here is an analog of the existing QANI/Apple NI interface


- app, scans for, and connects over BLE 
- - using same UUID for both Apple and Android
- - the BLE session is kept open 
- - - so that the app can send the STOP command,
- - - but not used for any other communications
- app sends Init command (MessageId_init for apple, Android_MessageId_init for Android)
- - device responds with some config data (typedef struct t1 below), most important is the HW address of the device 
	- id = MessageId_accessoryConfigurationData)
- app processes and starts ranging
  - as UWB controller
- app sends info to device (typedef struct t1 below), with the sessionID, sessionKey, channel, preamble, and HW address of the phone
  - - message id MessageId_configure_and_start for iphone, 
  - - Android_MessageId_configure_and_start for android
  - device fills in the same global structure used by Apple QANI, and then invokes the SAME process to range
  - - NOTE: because both iOS NI and Android use the same structure, pre-configured for NI,
    it is saved as part of QaniTask startup and restored by each interface as its used
  - device responds with MessageId_accessoryUwbDidStart (same id as for apple)
- when app is done ranging
- - app sends stop (same ID for both phones)
- - - device responds with MessageId_accessoryUwbDidStop (same ID for both phones)
- app closes BLE session

a pictorial of that 

```
phone                                      firmware

Connect 
   ----> init to the write characteristic 
                                           <---   notify with config structure (HW address)
.                                           
.                                           
   start ranging with accessory (HW address)
        -----> send config for accessory to use to range back to phone (Phone HW address)
                                                device accepts all parameters sent from phone
                                                sessionID, STS Key, channel, preamble, 
                                           start ranging with phone (Phone HW address)
                                           <---   notify Did_start 
.
.
.
        ----> Stop_ranging                 
                                           <---  Did_Stop
closes connection
```  


the message structure sent to the phone and sent back is

```
typedef struct t1 {
    uint8_t id;
           //     0x1A           // send from phone to request device config
                                 // send back to phone, sessionid and HW address
           //     0x1B           // send from phone to provide info to start rangning
                                 // after start ranging,  send MessageId_accessoryUwbDidStart , 1 byte
           //     STOP 
    uint8_t api_version;       // 01

    uint32_t  session_id;      // 32-bit tracking session ID, little endian                             
    uint32_t channel;          // UWB Channel (Typically 9) little endian
    uint32_t preamble_index;   // Preamble Index (Typically 11)  little endian

    uint16_t address_size;     // little endian
    uint8_t  tag_uwb_addr[2];  // Accessory's short 2-byte UWB MAC address big endian
                               // (byte array, MSB first as shown by the app)

    uint16_t discovery_token_size;  // not used in android, but size field present, value = 0
    uint32_t discovery_token;       // not used in android
    uint16_t session_key_size;      // STS session key size, 8 or 16 supported, little endian
    uint16_t session_key;           // the big endian string of bytes of the key

    uint8_t  aoa_enabled;      // 0 = Distance only (CDK), 1 = Azimuth + Distance (Murata)
} android_fira_tag_config_t;
```

