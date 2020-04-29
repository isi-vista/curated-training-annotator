# apf_ingester
Ingester that converts annotated .apf documents to the desired annotation format

# Initial Setup Instructions (Only needs to be done once)
- Generate an empty project by configuring parameters in apf_ingester_parameters.params with
event_list: ["Movement.Transport"]
and run apf_ingester.py with apf_ingester_parameters.params as the argument.
- Import the generated project into Inception
- Generate xmi files: Configure parameters in cas_creator_parameters.params and run AnnotatedCasCreator.py with apf_ingester_parameters.params as the argument.
- In Inception, in the project setting, under the documents tab, import all 1526 the generated xmi files as UIMA CAS XMI (Found in the cached_xmi directory in the project directory, or wherever specifed in the parameters file).
- Proceed to the export tab, and export this project as UIMA binary CAS
- Unzip the exported project
- Move the contents of the annotation_ser directory to the cached_annotation_ser directory in your home folder (or wherever specified in the apf_ingester_parameters.params file)

# Usage Instructions
- Enter list of users (strings) and list of event types (as EVENT_TYPE.SUBTYPE strings) in the apf_ingester_parameters.params file
- Run apf_ingester.py with the parameters file to generate desired projects