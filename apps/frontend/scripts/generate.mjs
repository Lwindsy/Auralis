import { readFile, writeFile, mkdir } from "node:fs/promises";
import { createHash } from "node:crypto";
import Ajv from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import standaloneCode from "ajv/dist/standalone/index.js";

const root = new URL("../../", import.meta.url);
const schemaText = await readFile(
  new URL(
    "intelligence/src/auralis_intelligence/contracts/agent-v1.schema.json",
    root,
  ),
  "utf8",
);
const apiText = await readFile(
  new URL("../contracts/agent-product-api.openapi.json", root),
  "utf8",
);
const schema = JSON.parse(schemaText);
const api = JSON.parse(apiText);
const ajv = new Ajv({ strict: false, code: { source: true }, allErrors: true });
addFormats(ajv);
ajv.addSchema(schema, "wire");
const routes = {};
const names = new Set();
function definition(value) {
  const ref = value?.content?.["application/json"]?.schema?.$ref;
  if (!ref) return null;
  const name = ref.split("/").at(-1);
  names.add(name);
  return name;
}
for (const [path, methods] of Object.entries(api.paths)) {
  for (const [method, operation] of Object.entries(methods)) {
    routes[`${method.toUpperCase()} ${path}`] = {
      request: definition(operation.requestBody),
      responses: Object.fromEntries(
        Object.entries(operation.responses).map(([status, response]) => [
          status,
          definition(response),
        ]),
      ),
    };
  }
}
const exports = {};
for (const name of [...names].sort()) {
  const id = `wire#/$defs/${name}`;
  ajv.getSchema(id);
  exports[name] = id;
}
const target = new URL("../src/generated/", import.meta.url);
await mkdir(target, { recursive: true });
await writeFile(
  new URL("validators.cjs", target),
  standaloneCode(ajv, exports),
);
await writeFile(
  new URL("routes.json", target),
  JSON.stringify(routes, null, 2) + "\n",
);
await writeFile(
  new URL("provenance.json", target),
  JSON.stringify(
    {
      product_revision: api.info.version,
      schema_sha256: createHash("sha256").update(schemaText).digest("hex"),
      openapi_sha256: createHash("sha256").update(apiText).digest("hex"),
    },
    null,
    2,
  ) + "\n",
);
const fixtures = JSON.parse(
  await readFile(
    new URL("intelligence/tests/fixtures/java-integration-cases.json", root),
    "utf8",
  ),
);
await writeFile(
  new URL("fixtures.json", target),
  JSON.stringify(
    fixtures.filter((row) => names.has(row.definition)),
    null,
    2,
  ) + "\n",
);
console.log("Generated product routes and validators from shared contracts.");
