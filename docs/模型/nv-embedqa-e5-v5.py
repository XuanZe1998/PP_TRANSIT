from openai import OpenAI

client = OpenAI(
  api_key="$NVIDIA_API_KEY",
  base_url="https://integrate.api.nvidia.com/v1"
)

response = client.embeddings.create(
    input=["What is the capital of France?"],
    model="nvidia/nv-embedqa-e5-v5",
    encoding_format="float",
    extra_body={"input_type": "query", "truncate": "NONE"}
)

print(response.data[0].embedding)
