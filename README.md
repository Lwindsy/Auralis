# Auralis

[中文](README_ZH.md)

Auralis helps you find your next album. Start with a record you love, pick a few genres, or describe what you feel like hearing. Try “something quiet for a late night, with a little jazz.”

**[Try Auralis](https://auralis-94-250-202-196.sslip.io/)** · No account needed

## Find an album

1. Add a favorite album, choose genres you like or want to avoid, or type an idea. Any one is enough to start.
2. Choose **Familiar** to stay close to your tastes, **Explore** to branch out, or **Surprise** to go further afield.
3. Browse albums as they arrive. Each card has a short review, and you can open a longer one to read more about the sound. If you already know an album, choose **Heard it** and Auralis will look for another suggestion.

Switch between English and Chinese in the top-left corner. You can close the tab and come back to your recommendations in the same browser. Auralis keeps submitted preferences and recommendations for 30 days after your last active use. The privacy details at the bottom of the site explain what gets stored and when OpenAI may process your input.

## Run the interface locally

With Node.js 22.12 or later, run these commands from the repository root.

```sh
npm --prefix apps/frontend ci
npm --prefix apps/frontend run dev:fixture
```

Open [http://127.0.0.1:4173](http://127.0.0.1:4173) to try the interface with sample albums. This mode runs without a database or API key and makes no model calls.

## For developers

- [Frontend](apps/frontend/package.json) — React and TypeScript handle album search, browsing and reviews. Run `npm --prefix apps/frontend test` for unit tests.
- [Backend](apps/backend/pom.xml) — Java 21 and Spring Boot serve the catalog and manage sessions and recommendations.
- [Recommendation service](apps/intelligence/pyproject.toml) — Python finds candidate albums, checks source material and generates reviews.

Running the full service requires a catalog index, matching music data, PostgreSQL and model credentials. The public checkout includes the application and a sample-data interface. See the [Linux deployment guide](apps/runtime/deployment.txt) or [Windows runtime guide](apps/runtime/usage.txt) for the required files, configuration and database migrations.

## License

[MIT](LICENSE)
