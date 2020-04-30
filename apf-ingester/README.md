# apf_ingester:
Ingester that converts annotated `.apf` documents to `.xmi` files, which can then be used to import these projects into INCEpTION.

# Initial Setup Instructions (Only needs to be done once):
- Generate an empty project by configuring parameters in `apf_ingester_parameters.params` with
`event_list: ["Movement.Transport"]` as a parameter
and run `apf_ingester.py` with `apf_ingester_parameters.params` as the argument.
- Import the generated project into Inception (Ensure the import user permissions checkbox is checked).
- Generate `.xmi` files: Run `AnnotatedCasCreator.py` with `apf_ingester_parameters.params` as the argument.
- In Inception, in the project setting, under the documents tab, import all 1526 the generated `.xmi` files as UIMA CAS XMI (Found in the `cached_xmi` directory in the project directory, or wherever specifed in the parameters file).
- Proceed to the export tab, and export this project as UIMA binary CAS.
- Unzip the exported project.
- Move the contents of the `annotation_ser` directory to the `cached_annotation_ser` directory in your home folder (or wherever specified in the parameters file)

# Usage Instructions:
- Enter list of users (strings) and list of event types (as EVENT_TYPE.SUBTYPE strings) in the `apf_ingester_parameters.params` file. To generate all events, use "All" (`event_list: ["All"]`).
- Run `apf_ingester.py` with the parameters file to generate desired projects

# Sample Parameters File (`apf_ingester_parameters.params`):
`json_template_path: "./project_template.json"
type_system_path: "TypeSystem.xml"
cas_xmi_template_path: "cas_xmi_template.xmi"
annotation_ser_path: ".\\cached_annotation_ser\\"
cached_xmi_path: ".\\cached_xmi\\"
cached_ace_data_path: "C:\\isi\\apf_ingester\\cached_ace_files\\"
output_dir_path: "C:\\isi\\apf_ingester\\apf_ingester_output\\"
corpus_paths: ["C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\bc\\adj\\",
               "C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\bn\\adj\\",
               "C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\cts\\adj\\",
               "C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\nw\\adj\\",
               "C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\un\\adj\\",
               "C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\wl\\adj\\"]
user_list: ["user1" ,"user2"]
event_list: ["All"]`