namespace RapiDrop.Core.Network;

public enum PacketType : ushort
{
    Ping = 0x0001,
    Pong = 0x0002,
    PairRequest = 0x0003,
    PairConfirm = 0x0004,
    DeviceInfo = 0x0005,
    PairInvite = 0x0006,
    PairFail = 0x0007,
    PairAccept = 0x0008,
    ClipText = 0x0010,
    ClipUrl = 0x0011,
    ClipImage = 0x0012,
    ClipFile = 0x0013,
    FileStart = 0x0014,
    FileChunk = 0x0015,
    FileEnd = 0x0016,
    FileCancel = 0x0017,
    Disconnect = 0x00FF,
    ConfigSync = 0x0020
}
