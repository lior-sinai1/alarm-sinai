import net from "node:net";

const SUBNET = "192.168.1";
const PORTS = [6668, 6667];
const TIMEOUT = 1500;

function checkHost(ip, port) {
  return new Promise((resolve) => {
    const socket = new net.Socket();
    socket.setTimeout(TIMEOUT);
    socket.once("connect", () => {
      socket.destroy();
      resolve(`${ip}:${port}`);
    });
    socket.once("timeout", () => { socket.destroy(); resolve(null); });
    socket.once("error", () => resolve(null));
    socket.connect(port, ip);
  });
}

async function batch(items, size, fn) {
  const out = [];
  for (let i = 0; i < items.length; i += size) {
    const chunk = items.slice(i, i + size);
    out.push(...(await Promise.all(chunk.map(fn))));
  }
  return out;
}

const ips = [];
for (let i = 1; i <= 254; i++) ips.push(`${SUBNET}.${i}`);

for (const port of PORTS) {
  const results = (await batch(ips, 40, (ip) => checkHost(ip, port))).filter(Boolean);
  console.log(`Port ${port} open on:`, results);
}
