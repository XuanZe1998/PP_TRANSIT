from openai import OpenAI
import json

client = OpenAI(
  base_url="https://integrate.api.nvidia.com/v1",
  api_key="$NVIDIA_API_KEY"
)

completion = client.chat.completions.create(
  model="stepfun-ai/step-3.5-flash",
  messages=[{"role":"user","content":""}],
  temperature=1,
  top_p=0.9,
  max_tokens=16384,
  stream=False
)


message = completion.choices[0].message

reasoning = getattr(message, "reasoning_content", None)
if reasoning:
  print(reasoning)

if message.content:
  print(message.content)