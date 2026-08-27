
from openai import OpenAI

client = OpenAI(
  api_key="$NVIDIA_API_KEY",
  base_url="https://integrate.api.nvidia.com/v1"
)

response = client.embeddings.create(
    input=["What is the civil caseload in South Dakota courts?"],
    model="nvidia/llama-nemotron-embed-vl-1b-v2",
    encoding_format="float",
    extra_body={"modality": ["text"], "input_type": "query", "truncate": "NONE"}
)

print(response.data[0].embedding)
