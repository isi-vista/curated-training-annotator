# apf_ingester:
Ingester that converts annotated `.apf` documents to `.xmi` files, which can then be used to import these projects into INCEpTION.

# Initial Setup Instructions (Only needs to be done once):
If the project `ACE-Test.Event-test_user` is already in the Inception projects list,
you can select that project and then skip to step 5.
1. Generate an empty project by configuring parameters in `apf_ingester_parameters.params` with
`user_list: ["test_user"]` and `event_list: ["Test.Event"]`,
then run `apf_ingester.py` with `apf_ingester_parameters.params` as the argument.
2. Log into Inception. In Administration --> Projects, import the generated project
(Ensure the import user permissions checkbox is checked).
3. Generate `.xmi` files: Run `AnnotatedCasCreator.py` with `apf_ingester_parameters.params` as the argument.
4. In Inception, select the generated project that you imported.
Under the `Documents` tab, import all 1526 generated `.xmi` files as UIMA CAS XMI (Found in the `cached_xmi` directory in the project directory, or wherever specifed in the parameters file).
5. Proceed to the export tab, and export this project as UIMA binary CAS.
6. Unzip the exported project.
7. Move the contents of the `annotation_ser` directory to the `cached_annotation_ser` directory in your home folder (or wherever specified in the parameters file)

# Usage Instructions:
- Enter list of users (strings) and list of event types (as EVENT_TYPE.SUBTYPE strings) in the `apf_ingester_parameters.params` file. To generate all events, use "All" (`event_list: ["All"]`).
- Run `apf_ingester.py` with the parameters file to generate desired projects

# Sample Parameters File (`apf_ingester_parameters.params`):
`json_template_path: "./project_template.json"`  
`type_system_path: "TypeSystem.xml"`  
`cas_xmi_template_path: "cas_xmi_template.xmi"`  
`annotation_ser_path: ".\\cached_annotation_ser\\"`  
`cached_xmi_path: ".\\cached_xmi\\"`  
`cached_ace_data_path: "C:\\isi\\apf_ingester\\cached_ace_files\\"`  
`output_dir_path: "C:\\isi\\apf_ingester\\apf_ingester_output\\"`  
`corpus_paths: ["C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\bc\\adj\\",`  
`               "C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\bn\\adj\\",`  
`               "C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\cts\\adj\\",`  
`               "C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\nw\\adj\\",`  
`               "C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\un\\adj\\",`  
`               "C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\wl\\adj\\"]`  
`user_list: ["user1" ,"user2"]`  
`event_list: ["All"]`  