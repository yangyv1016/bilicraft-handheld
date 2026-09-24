const headers = { Authorization: `Bearer ${process.env.CLOUDFLARE_API_TOKEN}` };
const account = process.env.CLOUDFLARE_ACCOUNT_ID;
for (const [label, path] of [
  ['Pages domains', `/accounts/${account}/pages/projects/bilicraft-cdn/domains`],
  ['Worker domains', `/accounts/${account}/workers/domains`],
  ['Worker scripts', `/accounts/${account}/workers/scripts`],
  ['Zone', '/zones?name=yanguiofficial.cn'],
]) {
  const response = await fetch(`https://api.cloudflare.com/client/v4${path}`, { headers });
  const data = await response.json();
  console.log(JSON.stringify({ label, status: response.status, errors: data.errors,
    result: data.result?.map(item => ({ id: item.id, name: item.name, hostname: item.hostname,
      service: item.service, environment: item.environment, status: item.status })) }));
  if (label === 'Zone' && response.ok) {
    for (const zone of data.result) {
      const routes = await fetch(`https://api.cloudflare.com/client/v4/zones/${zone.id}/workers/routes`, { headers });
      console.log(JSON.stringify({ label: 'Worker routes', status: routes.status, data: await routes.json() }));
    }
  }
}
