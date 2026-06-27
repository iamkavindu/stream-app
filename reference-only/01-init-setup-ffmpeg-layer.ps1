$endpoint = "http://localhost:4566"

aws --endpoint-url $endpoint lambda publish-layer-version `
--layer-name local-ffmpeg-layer `
--zip-file fileb://staging/ffmpeg-layer.zip `
--compatible-runtimes provided.al2023 `
--region us-east-1
