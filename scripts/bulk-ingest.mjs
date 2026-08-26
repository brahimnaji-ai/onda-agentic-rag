import { readFile, readdir } from 'node:fs/promises';
import { basename, resolve } from 'node:path';

const documentsDirectory = resolve(process.cwd(), 'documents', 'onda');
const endpoint = process.env.INGEST_URL ?? 'http://localhost:8080/api/v1/documents';
const token = process.env.INGEST_TOKEN;
const delayMs = Number(process.env.INGEST_DELAY_MS ?? 500);
const startFrom = Number(process.env.START_FROM ?? 1);

if (!token) {
  console.error('Missing INGEST_TOKEN. Set it to a bearer token for a user with the INGESTOR role.');
  process.exit(1);
}

const files = (await readdir(documentsDirectory, { withFileTypes: true }))
  .filter((entry) => entry.isFile())
  .map((entry) => resolve(documentsDirectory, entry.name))
  .sort((first, second) => first.localeCompare(second));

if (files.length === 0) {
  console.error(`No files found in ${documentsDirectory}`);
  process.exit(1);
}

if (!Number.isInteger(startFrom) || startFrom < 1 || startFrom > files.length) {
  console.error(`START_FROM must be a whole number from 1 to ${files.length}.`);
  process.exit(1);
}

const filesToUpload = files.slice(startFrom - 1);

const sleep = (milliseconds) => new Promise((done) => setTimeout(done, milliseconds));
let succeeded = 0;
const failed = [];

for (const [index, filePath] of filesToUpload.entries()) {
  const fileName = basename(filePath);
  const documentNumber = startFrom + index;
  process.stdout.write(`[${documentNumber}/${files.length}] Uploading ${fileName} ... `);

  try {
    const contents = await readFile(filePath);
    const form = new FormData();
    form.append('file', new Blob([contents]), fileName);

    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: form,
    });

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${(await response.text()).slice(0, 300)}`);
    }

    succeeded += 1;
    console.log('done');
  } catch (error) {
    failed.push(fileName);
    console.log(`FAILED — ${error.message}`);
  }

  if (delayMs > 0 && index < filesToUpload.length - 1) await sleep(delayMs);
}

console.log(`\nFinished: ${succeeded}/${filesToUpload.length} documents uploaded successfully.`);
if (failed.length > 0) {
  console.error(`Failed files:\n${failed.join('\n')}`);
  process.exitCode = 1;
}
