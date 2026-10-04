using System;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using UnityEngine;

namespace VrmDroid
{
    /// <summary>
    /// Receives tracking packets from the Android host over UDP. On the phone this is localhost;
    /// in the Editor you can point the phone's preview stream at your PC for testing.
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

        UdpClient _client;
        Thread _thread;
        volatile bool _running;

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
            try
            {
                _client = new UdpClient(new IPEndPoint(IPAddress.Any, port));
            }
            catch (SocketException e)
            {
                Debug.LogError($"[VrmDroid] Cannot listen on UDP {port}: {e.Message}");
                return;
            }
            _running = true;
            _thread = new Thread(Receive) { IsBackground = true, Name = "VrmDroid UDP" };
            _thread.Start();
        }

        void OnDisable()
        {
            _running = false;
            _client?.Close();
            _client = null;
            _thread?.Join(200);
            _thread = null;
        }

        void Receive()
        {
            var any = new IPEndPoint(IPAddress.Any, 0);
            while (_running)
            {
                try
                {
                    var data = _client.Receive(ref any);
                    lock (_lock)
                    {
                        if (TrackingPacket.TryParse(data, data.Length, _incoming))
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
