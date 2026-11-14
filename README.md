# Arreglador

A fixer/arranger of documents/images and other files.
Mainly will work with Google Drive and Photos to start with.

Possible high level TODO list

**Find original implementation for reference**

1. github repo
1. simple scan of directory
1. sqlite to store fingerpreints
   * Use https://github.com/JetBrains/Exposed 
1. connect to drive
1. connect to photos
1. check number of files in top directories
1. see if OCR exists in PDF files
1. parse zip files
1. find images without dates
1. use AI to classify/sort documents
1. ...

Possible schema

* File
  * id
  * size
  * hash
  * type (text, pdf, img, ...)
* FileInstance
  * id
  * fileId
  * path
  * name
  * last_modified_datetime
