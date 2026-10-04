using System;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using UnityEngine;

namespace VrmDroid
{
    /// <summary>
    /// Receives tracking packets from the Android host over UDP. On the phone this listens on
    /// loopback only (the host app is in the same process); in the Editor it listens on all
    /// interfaces so a phone on the LAN can be pointed at the PC for testing.
    /// </summary>
    public sealed class TrackingReceiver : MonoBehaviour
    {
        public const int DefaultPort = 39540;

        [SerializeField] int port = DefaultPort;

        readonly object _lock = new object();
        readonly float[] _incoming = new float[TrackingPacket.FloatCount];
        readonly float[] _latest = new float[TrackingPacket.FloatCount];
        long _receivedCount;
        long _consumedCount;
        DateTime _lastPacketUtc = DateTime.MinValue;

        Socket _socket;
        Thread _thread;
        volatile bool _running;

        /// <summary>Valid packets received so far; the difference over time is the tracking rate.</summary>
        public long ReceivedCount
        {
            get { lock (_lock) return _receivedCount; }
        }

        /// <summary>Seconds since the last packet, or infinity if none yet.</summary>
        public double SecondsSinceLastPacket
        {
            get
            {
                lock (_lock)
                {
                    return _lastPacketUtc == DateTime.MinValue
                        ? double.PositiveInfinity
                        : (DateTime.UtcNow - _lastPacketUtc).TotalSeconds;
                }
            }
        }

        void OnEnable()
        {
            var address = Application.isEditor ? IPAddress.Any : IPAddress.Loopback;
            Socket socket;
            try
            {
                socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
                socket.Bind(new IPEndPoint(address, port));
            }
            catch (SocketException e)
            {
                Debug.LogError($"[VrmDroid] Cannot listen on UDP {port}: {e.Message}");
                return;
            }
            _socket = socket;
            _running = true;
            // The thread owns its socket reference, so OnDisable clearing the field can't race it.
            _thread = new Thread(() => Receive(socket)) { IsBackground = true, Name = "VrmDroid UDP" };
            _thread.Start();
        }

        void OnDisable()
        {
            _running = false;
            _socket?.Close();
            _socket = null;
            _thread?.Join(200);
            _thread = null;
        }

        void Receive(Socket socket)
        {
            var buffer = new byte[2048]; // reused; packets are ~300 bytes
            while (_running)
            {
                try
                {
                    // Receive rather than ReceiveFrom: the sender doesn't matter, and ReceiveFrom
                    // allocates an endpoint per packet.
                    var length = socket.Receive(buffer);
                    lock (_lock)
                    {
                        if (TrackingPacket.TryParse(buffer, length, _incoming) && AllFinite(_incoming))
                        {
                            Array.Copy(_incoming, _latest, _latest.Length);
                            _receivedCount++;
                            _lastPacketUtc = DateTime.UtcNow;
                        }
                    }
                }
                catch (SocketException) { if (!_running) break; }
                catch (ObjectDisposedException) { break; }
            }
        }

        static bool AllFinite(float[] values)
        {
            foreach (var v in values)
            {
                if (float.IsNaN(v) || float.IsInfinity(v)) return false;
            }
            return true;
        }

        /// <summary>Copies the newest packet into <paramref name="into"/>; false if nothing new.</summary>
        public bool TryGetLatest(float[] into)
        {
            lock (_lock)
            {
                if (_receivedCount == _consumedCount) return false;
                _consumedCount = _receivedCount;
                Array.Copy(_latest, into, _latest.Length);
                return true;
            }
        }
    }
}
