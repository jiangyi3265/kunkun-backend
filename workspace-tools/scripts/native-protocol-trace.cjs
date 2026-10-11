// Never persist protocol parameters/results: they can contain credentials or PII.
const fs = require('node:fs');
module.exports = function trace(connection, file, stage) {
  const records = [], send = connection.send.bind(connection);
  const save = () => fs.writeFileSync(file, JSON.stringify(records, null, 2));
  connection.send = async (method, params) => {
    const record = { sequence: records.length + 1, method, stage: stage(), startedAt: new Date().toISOString(), status: 'pending' };
    records.push(record); save();
    try { const result = await send(method, params); record.status = 'completed'; return result; }
    catch (error) { record.status = 'failed'; record.errorType = error.name || 'Error'; throw error; }
    finally { record.finishedAt = new Date().toISOString(); save(); }
  };
  return records;
};
