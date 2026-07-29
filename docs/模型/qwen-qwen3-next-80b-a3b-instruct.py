from openai import OpenAI
import json

client = OpenAI(
  base_url="https://integrate.api.nvidia.com/v1",
  api_key="$NVIDIA_API_KEY"
)

completion = client.chat.completions.create(
  model="qwen/qwen3-next-80b-a3b-instruct",
  messages=[{"role":"user","content":""}],
  temperature=0.6,
  top_p=0.7,
  max_tokens=4096,
  stream=False
)


message = completion.choices[0].message

if message.content:
  print(message.content)