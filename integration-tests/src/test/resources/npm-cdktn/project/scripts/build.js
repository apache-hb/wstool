const cdktf = require('cdktn');

class AwsTerraformResource extends cdktf.TerraformResource {
  constructor(scope, id, terraformResourceType, attributes) {
    super(scope, id, { terraformResourceType });
    this.attributes = attributes;
  }

  synthesizeAttributes() {
    return this.attributes;
  }
}

const app = new cdktf.App();
const stack = new cdktf.TerraformStack(app, 'cdktn-s3-project');

new AwsTerraformResource(stack, 'Bucket', 'aws_s3_bucket', {
  bucket: 'cdktn-lifecycle-integration',
});

new AwsTerraformResource(
  stack,
  'BucketLifecycle',
  'aws_s3_bucket_lifecycle_configuration',
  {
    bucket: 'cdktn-lifecycle-integration',
    rule: [
      {
        id: 'expire-logs-after-30-days',
        status: 'Enabled',
        filter: { prefix: 'logs/' },
        expiration: { days: 30 },
      },
      {
        id: 'transition-to-glacier',
        status: 'Enabled',
        filter: { prefix: 'archive/' },
        transition: [{ days: 90, storage_class: 'GLACIER' }],
      },
    ],
  },
);

app.synth();
