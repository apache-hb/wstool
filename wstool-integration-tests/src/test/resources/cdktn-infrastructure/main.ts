import { App, TerraformStack } from "cdktn";
import { file, provider as local } from "@cdktn/provider-local";
import { provider as random, stringResource } from "@cdktn/provider-random";

const app = new App();
const stack = new TerraformStack(app, "wstool-test-infrastructure");

new random.RandomProvider(stack, "random");
new local.LocalProvider(stack, "local");

const bucketName = new stringResource.StringResource(stack, "bucketName", {
  length: 8,
  special: false,
  upper: false,
});
new file.File(stack, "bucketDefinition", {
  filename: "wstool-test-infrastructure.json",
  content: JSON.stringify({
    bucket: `wstool-test-infrastructure-${bucketName.result}`,
    lifecycleRules: [
      {
        id: "expire-temporary-objects",
        enabled: true,
        prefix: "temporary/",
        expiration: { days: 30 },
      },
      {
        id: "archive-logs",
        enabled: true,
        prefix: "logs/",
        transition: [{ days: 60, storageClass: "GLACIER" }],
      },
    ],
  }, null, 2),
});

app.synth();
