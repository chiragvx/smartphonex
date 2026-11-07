// Points the phone at a relay. Run while the phone is reachable locally, such as over USB:
//   adb forward tcp:8080 tcp:8080
//   adb exec-out run-as com.smartphonex.app cat files/token.txt > .tooling/token.txt
//   node tools/enroll.mjs localhost:8080 <phone-token> <relay-url> <vehicle-key>
const [phone, token, url, key] = process.argv.slice(2);
if (!url) { console.error('usage: node tools/enroll.mjs <phone-host:port> <phone-token> <relay-url> <vehicle-key>'); process.exit(2); }
const res = await fetch(`http://${phone}/relay/config`, {
  method: 'POST',
  headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
  body: JSON.stringify({ url, key }),
});
console.log(res.status, await res.text());
process.exit(res.ok ? 0 : 1);
