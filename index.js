import express from "express";
import OpenAI from "openai";

const app = express();

// ВАЖНО: ловим любые content-type и не падаем на пустом JSON
app.use(express.json({ type: "*/*" }));

function aliceResponse(text, end = false) {
  return {
    version: "1.0",
    response: { text, end_session: end }
  };
}

// Если Express не смог распарсить JSON — он кидает SyntaxError и обычно отдаёт 400.
// Мы перехватываем и отвечаем 200 нормальным JSON для Алисы.
app.use((err, req, res, next) => {
  if (err instanceof SyntaxError) {
    return res.status(200).json(
      aliceResponse("Я получила пустой запрос. Скажи ещё раз: задай вопрос.")
    );
  }
  next(err);
});

const openai = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });

app.post("/", async (req, res) => {
  try {
    const body = req.body || {};
    const userText = body?.request?.original_utterance?.trim() || "";
    const isNew = body?.session?.new === true;

    if (!process.env.OPENAI_API_KEY) {
      return res.json(aliceResponse("На сервере не настроен ключ OpenAI. Проверь переменную OPENAI_API_KEY в Render."));
    }

    if (isNew && !userText) {
      return res.json(aliceResponse("Привет! Скажи вопрос — отвечу через ChatGPT."));
    }

    if (["выход", "стоп", "пока"].includes(userText.toLowerCase())) {
      return res.json(aliceResponse("Окей, пока!", true));
    }

    // Чтобы не тянуть время, отвечаем коротко
    const ai = await openai.responses.create({
      model: "gpt-5-mini",
      input: `Ответь по-русски, коротко (1-3 предложения): ${userText}`
    });

    const answer = (ai.output_text || "Не смог ответить, попробуй перефразировать.").trim();
    res.json(aliceResponse(answer));
  } catch (e) {
    console.error(e);
    res.json(aliceResponse("Произошла ошибка на сервере. Попробуй ещё раз."));
  }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log("Server started on port", PORT));
