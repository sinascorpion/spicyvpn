const { Engine } = require('./engine.cjs');
const engine = new Engine(process.argv[2], process.argv[3]);
const send = value => { if (process.connected) process.send(value); };
engine.on('state', value => send({ event: 'state', value }));
engine.on('log', value => send({ event: 'log', value }));
process.on('message', async ({ id, method, args }) => {
  try {
    let value;
    if (method === 'recover') value = await engine.system.recover();
    else if (method === 'connect') value = await engine.connect(...args);
    else if (method === 'disconnect') value = await engine.disconnect();
    else throw new Error('Invalid worker command');
    send({ id, value });
  } catch (error) { send({ id, error: error.message }); }
});
// The worker owns the native processes and restores the OS even if the GUI dies.
process.once('disconnect', () => engine.disconnect().finally(() => process.exit()));
