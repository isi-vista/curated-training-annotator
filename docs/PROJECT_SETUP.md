To setup a project for curated training for an ACE/ERE-like event task:

1. Define a tagset called `CTEventSpanTypes` with tags `trigger`, `interesting` and then each of the argument types of the target event (e.g. `Attacker`, `Target`, `Instrument`, `Time`, `Place`).
2. Create a layer called `CTEventSpan`.  
It should be of `Span` type and character-level granularity.  
It should have one feature, `negative_example` of type `Primitive:Boolean`.
3. Create a second layer called `CTEventSpanType`.
"Attach to layer" `CTEventSpan`. 
Add a `String` feature of called `relation_type` with tagset `CTEventSpanTypes`.
4. Click "Document Repositories" and set up your corpus search repository.

This is a total abuse of Inception's data model, but it allows faster annotation and we can sort it all out on the back end.

The intended workflow is that when an event of interest is present in the sentence, the user will first click one of the trigger words to create a `CTEventSpan`.
Then she will click on all alternate triggers, arguments, and "interesting" words in the sentence to create `CTEventSpan`s for those.
Finally, each of these will be connected with `CTEventSpanType` edges to the first trigger clicked, with an edge type corresponding to the sort of thing the highlighted span is.
