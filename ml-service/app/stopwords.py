"""
Stopword vocabulary used for *labelling*.

Kept separate from `preprocessing.STOPWORDS` (which is a conservative list used
when cleaning comment text) because labels have the opposite requirement: a topic
label must never be built out of filler. This is why the old pipeline could
produce labels like "To / The" — the label vectorizer had no stopword list at all
on the BERTopic path. Anything in here is banned from appearing in a label.
"""
from sklearn.feature_extraction.text import ENGLISH_STOP_WORDS

# Chat/YouTube filler that is frequent but carries no topical meaning.
YOUTUBE_NOISE = {
    # greetings / reactions
    "hi", "hello", "hey", "yo", "bro", "bruh", "sir", "madam", "mam", "dude", "guys",
    "man", "buddy", "friend", "everyone", "thanks", "thank", "thx", "ty", "welcome",
    "congrats", "congratulations", "lol", "lmao", "omg", "wow", "haha", "hahaha",
    "nice", "good", "great", "awesome", "amazing", "excellent", "perfect", "best",
    "love", "loved", "like", "liked", "cool", "super", "wonderful", "beautiful",
    "fantastic", "brilliant", "helpful", "useful", "clear", "easy", "hard",
    "please", "pls", "plz", "kindly", "sorry", "yes", "no", "ok", "okay", "yeah",
    "yep", "nope", "sure", "true", "right", "wrong", "bad", "worst", "boring",
    # channel/meta words — real, but never a *topic*
    "video", "videos", "vid", "vids", "channel", "channels", "content", "upload",
    "uploads", "uploaded", "subscribe", "subscribed", "subscriber", "subscribers",
    "sub", "subs", "like", "likes", "comment", "comments", "watch", "watching",
    "watched", "views", "view", "youtube", "yt", "playlist", "series", "episode",
    "part", "intro", "outro", "tutorial", "tutorials", "guide", "guides", "lesson",
    "course", "clip", "stream", "livestream", "podcast", "short", "shorts",
    # request verbs — the trigger, not the subject
    "make", "made", "making", "do", "does", "did", "doing", "create", "created",
    "cover", "covered", "explain", "explained", "show", "showed", "teach", "talk",
    "discuss", "review", "reviewed", "post", "share", "bring", "give", "want",
    "wanted", "need", "needed", "wish", "hope", "waiting", "wait", "next", "more",
    "another", "again", "soon", "asap", "someday", "sometime",
    # generic time/quantity
    "today", "tomorrow", "yesterday", "day", "days", "week", "weeks", "month",
    "months", "year", "years", "time", "times", "now", "then", "ever", "never",
    "always", "one", "two", "three", "first", "last", "new", "old", "much", "many",
    "lot", "lots", "bit", "little", "big", "small", "long", "short",
    "ago", "later", "earlier", "before", "after", "soon", "recently", "currently",
    "everyone", "everybody", "someone", "somebody", "nobody", "anyone",
    # netspeak / contractions the tokenizer leaves behind
    "u", "ur", "im", "ive", "id", "ill", "dont", "doesnt", "didnt", "cant", "cont",
    "couldnt", "wouldnt", "shouldnt", "isnt", "arent", "wasnt", "werent", "wont",
    "havent", "hasnt", "thats", "theres", "heres", "whats", "its", "youre", "theyre",
    "gonna", "wanna", "gotta", "kinda", "sorta", "pleaseee", "sirr",
    "really", "very", "just", "also", "even", "still", "already", "maybe", "actually",
    "basically", "literally", "definitely", "probably", "sure", "though", "although",
    # comparatives / vague nouns — these turn "make it longer next time" into a
    # bogus topic unless they are treated as filler.
    "longer", "shorter", "louder", "quieter", "faster", "slower", "better", "worse",
    "bigger", "smaller", "easier", "harder", "simpler", "deeper", "quicker",
    "clearer", "detailed", "detail", "details", "explanation", "example", "examples",
    "stuff", "thing", "things", "topic", "topics", "idea", "ideas", "suggestion",
    "suggestions", "request", "requests", "question", "questions", "problem", "issue",
}

# Union of sklearn's English list and our own noise list.
LABEL_STOPWORDS = set(ENGLISH_STOP_WORDS) | YOUTUBE_NOISE

# Leading words stripped off a captured request subject ("a video on X" -> "X").
SUBJECT_LEAD_STRIP = {
    "a", "an", "the", "some", "any", "your", "you", "us", "me", "my", "our", "this",
    "that", "these", "those", "it", "video", "videos", "vid", "tutorial", "guide",
    "series", "content", "please", "pls", "plz", "kindly", "more", "about", "on",
    "for", "of", "regarding", "how", "to", "and", "or", "next", "another", "new",
    "detailed", "full", "complete", "quick", "short", "long", "one", "part",
}

# Trailing words stripped off a captured request subject ("X please bro" -> "X").
SUBJECT_TAIL_STRIP = {
    "please", "pls", "plz", "kindly", "thanks", "thank", "thx", "ty", "bro", "bruh",
    "sir", "madam", "mam", "dude", "man", "guys", "asap", "soon", "too", "also",
    "video", "videos", "vid", "tutorial", "guide", "series", "next", "again",
    "ok", "okay", "yeah", "yes", "and", "or", "but", "so", "then", "now", "if",
    "a", "an", "the", "of", "for", "on", "in", "to", "with", "about", "please",
}


def content_tokens(text: str):
    """Lowercased alphanumeric tokens with all label-stopwords removed."""
    out = []
    for raw in str(text).lower().replace("/", " ").split():
        tok = "".join(ch for ch in raw if ch.isalnum() or ch in "+#.-")
        tok = tok.strip(".-")
        if len(tok) < 2:
            continue
        if tok in LABEL_STOPWORDS:
            continue
        if tok.isdigit():
            continue
        out.append(tok)
    return out


def has_content(text: str) -> bool:
    """True when the text carries at least one non-filler token."""
    return len(content_tokens(text)) > 0


def topic_key(text: str) -> str:
    """Order- and plural-insensitive identity for a topic label.

    Collapses "Price"/"Prices" and "cable management"/"management cable" onto one
    key so near-duplicate topics cannot each take a slot in the ranking.
    """
    singular = set()
    for tok in content_tokens(text):
        # "boxes"/"dishes"/"classes" lose "es"; everything else loses just the "s",
        # so "prices" -> "price" and "cases" -> "case" rather than "pric"/"cas".
        # Note "sses" (classes), not "ses", or "cases" would be mangled.
        if len(tok) > 4 and tok.endswith(("sses", "xes", "zes", "ches", "shes")):
            tok = tok[:-2]
        elif len(tok) > 3 and tok.endswith("s") and not tok.endswith("ss"):
            tok = tok[:-1]
        singular.add(tok)
    return " ".join(sorted(singular))
