import express from "express";
import OpenAI from "openai";

const app = express();
app.use(express.json());

const openai = new OpenAI({
  apiKey: process.env.OPENAI_API_KEY
});

function aliceResponse(text, end = false) {
  return {
    version: "1.0",
    response: {
      text,
      end_session: end
    }
  };
}

app.post("/", async (req, res) => {
  try {
    const body = req.body;
    const userText = body?.request?.original_utterance || "";
    const isNew = body?.session?.new;

    if (isNew && !userText) {
      return res.json(
        aliceResponse("Привет! Спроси меня о чём угодно.")
      );
    }

    if (["выход", "стоп", "пока"].includes(userText.toLowerCase())) {
      return res.json(
        aliceResponse("Хорошо, пока!", true)
      );
    }

    const ai = await openai.responses.create({
      model: "gpt-5-mini",
      input: `Ответь коротко и понятно по-русски: ${userText}`
    });

    const answer =
      ai.output_text || "Я не смог ответить.";

    res.json(aliceResponse(answer));
  } catch (err) {
    console.error(err);
    res.json(
      aliceResponse("Произошла ошибка на сервере.")
    );
  }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log("Server started on port", PORT);
});
